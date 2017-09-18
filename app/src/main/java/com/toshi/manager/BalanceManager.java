/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.manager;


import android.content.Context;
import android.content.SharedPreferences;

import com.toshi.crypto.HDWallet;
import com.toshi.manager.network.CurrencyService;
import com.toshi.manager.network.EthereumService;
import com.toshi.model.local.Network;
import com.toshi.model.local.Networks;
import com.toshi.model.network.Balance;
import com.toshi.model.network.Currencies;
import com.toshi.model.network.ExchangeRate;
import com.toshi.model.network.GcmDeregistration;
import com.toshi.model.network.GcmRegistration;
import com.toshi.model.network.ServerTime;
import com.toshi.model.sofa.Payment;
import com.toshi.util.CurrencyUtil;
import com.toshi.util.FileNames;
import com.toshi.util.GcmUtil;
import com.toshi.util.LogUtil;
import com.toshi.util.SharedPrefsUtil;
import com.toshi.view.BaseApplication;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

import rx.Completable;
import rx.Single;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subjects.BehaviorSubject;

public class BalanceManager {

    private final static BehaviorSubject<Balance> balanceObservable = BehaviorSubject.create();
    private static final String LAST_KNOWN_BALANCE = "lkb";
    private static final String ETH_SERVICE_SENT_TOKEN_TO_SERVER = "sentTokenToServer_v2";

    private HDWallet wallet;
    private SharedPreferences prefs;
    private SharedPreferences gcmPrefs;

    /* package */ BalanceManager() {
    }

    public BehaviorSubject<Balance> getBalanceObservable() {
        return balanceObservable;
    }

    public Completable init(final HDWallet wallet) {
        this.wallet = wallet;
        initCachedBalance();
        attachConnectivityObserver();
        return registerGcm(false);
    }

    private void initCachedBalance() {
        this.prefs = BaseApplication.get().getSharedPreferences(FileNames.BALANCE_PREFS, Context.MODE_PRIVATE);
        this.gcmPrefs = BaseApplication.get().getSharedPreferences(FileNames.GCM_PREFS, Context.MODE_PRIVATE);
        final Balance cachedBalance = new Balance(readLastKnownBalance());
        handleNewBalance(cachedBalance);
    }

    private void attachConnectivityObserver() {
        BaseApplication
                .get()
                .isConnectedSubject()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        __ -> this.refreshBalance(),
                        this::handleConnectionStateError
                );
    }

    private void handleConnectionStateError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error checking connection state", throwable);
    }

    public void refreshBalance() {
            EthereumService
                .getApi()
                .getBalance(this.wallet.getPaymentAddress())
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(
                        this::handleNewBalance,
                        this::handleBalanceError
                );
    }

    private void handleNewBalance(final Balance balance) {
        writeLastKnownBalance(balance);
        balanceObservable.onNext(balance);
    }

    private void handleBalanceError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error while fetching balance", throwable);
    }

    private Single<ExchangeRate> getLocalCurrencyExchangeRate() {
        return getLocalCurrency()
                .flatMap((code) -> fetchLatestExchangeRate(code)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()));
    }

    private Single<ExchangeRate> fetchLatestExchangeRate(final String code) {
        return CurrencyService
                .getApi()
                .getRates(code);
    }

    public Single<Currencies> getCurrencies() {
        return CurrencyService
                .getApi()
                .getCurrencies()
                .subscribeOn(Schedulers.io());
    }

    public Single<String> convertEthToLocalCurrencyString(final BigDecimal ethAmount) {
        return getLocalCurrencyExchangeRate()
                 .flatMap((exchangeRate) -> mapToString(exchangeRate, ethAmount));
    }

    private Single<String> mapToString(final ExchangeRate exchangeRate,
                               final BigDecimal ethAmount) {
        return Single.fromCallable(() -> {

            final BigDecimal marketRate = exchangeRate.getRate();
            // Do a bit of fuzzy rounding. This may be dangerous.
            final BigDecimal localAmount = marketRate
                    .multiply(ethAmount)
                    .setScale(1, BigDecimal.ROUND_HALF_UP);

            final DecimalFormat numberFormat = CurrencyUtil.getNumberFormat();
            numberFormat.setGroupingUsed(true);
            numberFormat.setMaximumFractionDigits(2);
            numberFormat.setMinimumFractionDigits(2);

            final String amount = numberFormat.format(localAmount);
            final String currencyCode = CurrencyUtil.getCode(exchangeRate.getTo());
            final String currencySymbol = CurrencyUtil.getSymbol(exchangeRate.getTo());

            return String.format("%s%s %s", currencySymbol, amount, currencyCode);
        });
    }

    private Single<String> getLocalCurrency() {
        return Single.fromCallable(SharedPrefsUtil::getCurrency);
    }

    public Single<BigDecimal> convertEthToLocalCurrency(final BigDecimal ethAmount) {
        return getLocalCurrencyExchangeRate()
                .flatMap((exchangeRate) -> mapToLocalCurrency(exchangeRate, ethAmount));
    }

    private Single<BigDecimal> mapToLocalCurrency(final ExchangeRate exchangeRate,
                                                  final BigDecimal ethAmount) {
        return Single.fromCallable(() -> {
            final BigDecimal marketRate = exchangeRate.getRate();
            return marketRate.multiply(ethAmount);
        });
    }

    public Single<BigDecimal> convertLocalCurrencyToEth(final BigDecimal localAmount) {
        return getLocalCurrencyExchangeRate()
                .flatMap((exchangeRate) -> mapToEth(exchangeRate, localAmount));
    }

    private Single<BigDecimal> mapToEth(final ExchangeRate exchangeRate,
                                        final BigDecimal localAmount) {
        return Single.fromCallable(() -> {
            if (localAmount.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }

            final BigDecimal marketRate = exchangeRate.getRate();
            if (marketRate.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }
            return localAmount.divide(marketRate, 8, RoundingMode.HALF_DOWN);
        });
    }

    public Completable unregisterFromGcm(final String token) {
        return EthereumService
                .getApi()
                .getTimestamp()
                .subscribeOn(Schedulers.io())
                .flatMapCompletable((st) -> unregisterGcmWithTimestamp(token, st));
    }

    private Completable registerForGcmWithTimestamp(final String token, final ServerTime serverTime) {
        if (serverTime == null) {
            throw new IllegalStateException("ServerTime was null");
        }

        return EthereumService
                .getApi()
                .registerGcm(serverTime.get(), new GcmRegistration(token, wallet.getPaymentAddress()))
                .toCompletable();
    }

    private Completable unregisterGcmWithTimestamp(final String token, final ServerTime serverTime) {
        if (serverTime == null) {
            return Completable.error(new IllegalStateException("Unable to fetch server time"));
        }

        return EthereumService
                .getApi()
                .unregisterGcm(serverTime.get(), new GcmDeregistration(token))
                .toCompletable();
    }

    /* package */ Single<Payment> getTransactionStatus(final String transactionHash) {
        return EthereumService
                .get()
                .getStatusOfTransaction(transactionHash);
    }

    private String readLastKnownBalance() {
        return this.prefs
                .getString(LAST_KNOWN_BALANCE, "0x0");
    }

    private void writeLastKnownBalance(final Balance balance) {
        this.prefs
                .edit()
                .putString(LAST_KNOWN_BALANCE, balance.getUnconfirmedBalanceAsHex())
                .apply();
    }

    //Don't unregister the default network
    public Completable changeNetwork(final Network network) {
        if (Networks.getInstance().onDefaultNetwork()) {
            return changeEthBaseUrl(network)
                    .andThen(registerGcm(true))
                    .subscribeOn(Schedulers.io())
                    .doOnCompleted(() -> SharedPrefsUtil.setCurrentNetwork(network));
        }

        return unregisterEthGcm()
                .andThen(changeEthBaseUrl(network))
                .andThen(registerGcm(true))
                .subscribeOn(Schedulers.io())
                .doOnCompleted(() -> SharedPrefsUtil.setCurrentNetwork(network));
    }

    private Completable unregisterEthGcm() {
        return GcmUtil
                .getGcmToken()
                .flatMapCompletable(token ->
                        BaseApplication
                        .get()
                        .getBalanceManager()
                        .unregisterFromGcm(token));
    }

    private Completable changeEthBaseUrl(final Network network) {
        return Completable.fromAction(() -> EthereumService.get().changeBaseUrl(network.getUrl()));
    }

    public Completable registerGcm(final boolean forceUpdate) {
        return GcmUtil
                .getGcmToken()
                .flatMapCompletable(token -> registerEthereumServiceGcmToken(token, forceUpdate));
    }

    private Completable registerEthereumServiceGcmToken(final String token, final boolean forceUpdate) {
        final boolean isSentToServer = this.gcmPrefs.getBoolean(ETH_SERVICE_SENT_TOKEN_TO_SERVER, false);
        if (!forceUpdate && isSentToServer) {
            return Completable.complete();
        }

        return BaseApplication
                .get()
                .getBalanceManager()
                .registerForGcm(token)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .doOnCompleted(() -> setSentToSever(ETH_SERVICE_SENT_TOKEN_TO_SERVER, true))
                .doOnError(this::handleGcmRegisterError);
    }

    private Completable registerForGcm(final String token) {
        return EthereumService
                .getApi()
                .getTimestamp()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .flatMapCompletable((st) -> registerForGcmWithTimestamp(token, st));
    }

    private void handleGcmRegisterError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error during registering of GCM " + throwable.getMessage());
        setSentToSever(ETH_SERVICE_SENT_TOKEN_TO_SERVER, false);
    }

    private void setSentToSever(final String key, final boolean value) {
        this.gcmPrefs.edit().putBoolean(key, value).apply();
    }

    public void clear() {
        this.prefs
                .edit()
                .clear()
                .apply();
    }
}
