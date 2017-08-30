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

/**
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.toshi.service;

import android.os.Bundle;

import com.google.android.gms.gcm.GcmListenerService;
import com.toshi.R;
import com.toshi.crypto.HDWallet;
import com.toshi.crypto.signal.model.DecryptedSignalMessage;
import com.toshi.crypto.util.TypeConverter;
import com.toshi.model.local.Recipient;
import com.toshi.model.local.User;
import com.toshi.model.sofa.Payment;
import com.toshi.model.sofa.SofaAdapters;
import com.toshi.model.sofa.SofaMessage;
import com.toshi.model.sofa.SofaType;
import com.toshi.util.EthUtil;
import com.toshi.util.LogUtil;
import com.toshi.util.SharedPrefsUtil;
import com.toshi.view.BaseApplication;
import com.toshi.view.notification.ChatNotificationManager;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

import rx.Completable;
import rx.Single;

public class GcmMessageReceiver extends GcmListenerService {

    @Override
    public void onMessageReceived(final String from, final Bundle data) {
        if (SharedPrefsUtil.hasSignedOut()) return;

        tryInitApp()
        .subscribe(
                () -> handleIncomingMessage(data),
                this::handleIncomingMessageError
        );
    }

    private void handleIncomingMessageError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error during incoming message", throwable);
    }

    private Completable tryInitApp() {
        return BaseApplication
                .get()
                .getToshiManager()
                .tryInit();
    }

    private void handleIncomingMessage(final Bundle data) {
        try {
            final String messageBody = data.getString("message");
            LogUtil.i(getClass(), "Incoming PN: " + messageBody);

            if (messageBody == null) {
                tryShowSignalMessage();
                return;
            }

            final SofaMessage sofaMessage = new SofaMessage().makeNew(messageBody);

            if (sofaMessage.getType() == SofaType.PAYMENT) {
                final Payment payment = SofaAdapters.get().paymentFrom(sofaMessage.getPayload());
                checkIfUserIsBlocked(payment);
            } else {
                tryShowSignalMessage();
            }

        } catch (final Exception ex) {
            LogUtil.exception(getClass(), ex);
        }
    }

    private void checkIfUserIsBlocked(final Payment payment) {
        isUserBlocked(payment.getFromAddress())
                .subscribe(
                        isBlocked -> handlePayment(isBlocked, payment),
                        __ -> handlePayment(payment)
                );
    }

    private void handlePayment(final boolean isBlocked,
                               final Payment payment) {
        if (isBlocked) return;
        handlePayment(payment);
    }

    private Single<Boolean> isUserBlocked(final String paymentAddress) {
        return getUserFromPaymentAddress(paymentAddress)
                .flatMap(user ->
                        BaseApplication
                        .get()
                        .getRecipientManager()
                        .isUserBlocked(user.getToshiId())
                );
    }

    private void handlePayment(final Payment payment) {
        payment.getPaymentDirection()
               .subscribe(
                   (paymentDirection) -> this.handleValidPayment(payment, paymentDirection),
                   this::handleInvalidPayment
               );
    }

    private void handleValidPayment(final Payment payment, final @Payment.PaymentDirection Integer paymentDirection) {
        switch (paymentDirection) {
            case Payment.FROM_LOCAL_USER:
                updatePayment(payment);
                refreshBalance();
                return;
            case Payment.TO_LOCAL_USER:
                addIncomingPayment(payment);
                refreshBalance();
                showPaymentNotification(payment);
                return;
            default:
            case Payment.NOT_RELEVANT:
                handleInvalidPayment(new IllegalArgumentException("Not handling transaction that doesn't involve local user"));
        }
    }

    private void handleInvalidPayment(final Throwable throwable) {
        LogUtil.exception(getClass(), "Invalid payment", throwable);
    }

    private void tryShowSignalMessage() {
        final DecryptedSignalMessage signalMessage;
        try {
            signalMessage = BaseApplication
                .get()
                .getSofaMessageManager()
                .fetchLatestMessage();
        } catch (final TimeoutException e) {
            LogUtil.i(getClass(), "Fetched all new messages");
            return;
        }

        ChatNotificationManager.showNotification(signalMessage);
        // There may be more messages.
        tryShowSignalMessage();

    }

    private void updatePayment(final Payment payment) {
        BaseApplication
                .get()
                .getTransactionManager()
                .updatePayment(payment);
    }

    private void addIncomingPayment(final Payment payment) {
        BaseApplication
                .get()
                .getTransactionManager()
                .addIncomingPayment(payment);
    }

    private void refreshBalance() {
        BaseApplication
                .get()
                .getBalanceManager()
                .refreshBalance();
    }

    private void showPaymentNotification(final Payment payment) {
        if (payment.getStatus().equals(SofaType.CONFIRMED)) {
            return;
        }

        BaseApplication
                .get()
                .getToshiManager()
                .getWallet()
                .subscribe(
                        (wallet) -> showOnlyIncomingPaymentNotification(wallet, payment),
                        this::handleError
                );
    }

    private void showOnlyIncomingPaymentNotification(final HDWallet wallet, final Payment payment) {
        if (wallet.getPaymentAddress().equals(payment.getFromAddress())) {
            // This payment was sent by us. Show no notification.
            LogUtil.i(getClass(), "Suppressing payment notification. Payment sent by local user.");
            return;
        }
        renderNotificationForPayment(payment);
    }

    private void renderNotificationForPayment(final Payment payment) {
        final BigInteger weiAmount = TypeConverter.StringHexToBigInteger(payment.getValue());
        final BigDecimal ethAmount = EthUtil.weiToEth(weiAmount);

        BaseApplication
                .get()
                .getBalanceManager()
                .convertEthToLocalCurrencyString(ethAmount)
                .subscribe(
                        (localCurrency) -> handleLocalCurrency(payment, localCurrency),
                        this::handleError
                );
    }

    private void handleLocalCurrency(final Payment payment, final String localCurrency) {
        final String content = String.format(Locale.getDefault(), this.getString(R.string.latest_message__payment_incoming), localCurrency);
        getUserFromPaymentAddress(payment.getFromAddress())
                .map(Recipient::new)
                .subscribe(
                        (sender) -> ChatNotificationManager.showChatNotification(sender, content),
                        __ -> ChatNotificationManager.showChatNotification(null, content)
                );
    }

    private Single<User> getUserFromPaymentAddress(final String paymentAddress) {
        return BaseApplication
                .get()
                .getRecipientManager()
                .getUserFromPaymentAddress(paymentAddress);
    }

    private void handleError(final Throwable throwable) {
        LogUtil.exception(getClass(), throwable);
    }
}
