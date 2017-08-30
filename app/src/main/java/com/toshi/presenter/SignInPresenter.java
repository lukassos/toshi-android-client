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

package com.toshi.presenter;

import android.content.Intent;
import android.support.annotation.StringRes;
import android.support.v4.app.ActivityCompat;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.google.common.base.Joiner;
import com.toshi.R;
import com.toshi.crypto.HDWallet;
import com.toshi.util.LogUtil;
import com.toshi.util.SharedPrefsUtil;
import com.toshi.view.BaseApplication;
import com.toshi.view.activity.MainActivity;
import com.toshi.view.activity.SignInActivity;
import com.toshi.view.activity.SignInInfoActivity;

import org.bitcoinj.crypto.MnemonicCode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import rx.Completable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

public class SignInPresenter implements Presenter<SignInActivity> {

    private static final int PASSPHRASE_LENGTH = 12;

    private SignInActivity activity;
    private boolean firstTimeAttaching = true;
    private CompositeSubscription subscriptions;

    private boolean onGoingTask = false;

    @Override
    public void onViewAttached(SignInActivity view) {
        this.activity = view;
        if (this.firstTimeAttaching) {
            this.firstTimeAttaching = false;
            initLongLivingObjects();
        }

        initShortLivingObjects();
    }

    private void initLongLivingObjects() {
        this.subscriptions = new CompositeSubscription();
        initWordList();
    }

    private void initShortLivingObjects() {
        initSignInPassphraseView();
        initClickListeners();
    }

    private void initWordList() {
        try {
            final List<String> wordList = new MnemonicCode().getWordList();
            addWordListToViewPasshraseView(wordList);
        } catch (IOException e) {
            LogUtil.e(getClass(), e.toString());
        }
    }

    private void addWordListToViewPasshraseView(final List<String> wordList) {
        this.activity.getBinding().passphraseInputView.setWordList((ArrayList<String>) wordList);
    }

    private void initSignInPassphraseView() {
        this.activity.getBinding().passphraseInputView
                .setOnPassphraseFinishListener(this::handlePassphraseFinished)
                .setOnPassphraseUpdateListener(this::updateSignInButton);
    }

    private void handlePassphraseFinished(final List<String> passphrase) {
        if (this.activity == null) return;
        final Button signIn = this.activity.getBinding().signIn;
        signIn.setText(R.string.sign_in);
        signIn.setBackgroundResource(R.drawable.background_with_radius_primary_color);
        signIn.setEnabled(true);
    }

    private void updateSignInButton(final int approvedWords) {
        final int wordsLeft = PASSPHRASE_LENGTH - approvedWords;
        if (wordsLeft > 0) {
            final String wordsLeftString = this.activity.getResources().getQuantityString(R.plurals.words, wordsLeft, wordsLeft);
            disableSignIn(wordsLeftString);
        }
    }

    private void initClickListeners() {
        this.activity.getBinding().closeButton.setOnClickListener(__ -> this.activity.finish());
        this.activity.getBinding().infoView.setOnClickListener(__ -> handleInfoViewClicked());
        this.activity.getBinding().signIn.setOnClickListener(v -> handleSignInClicked());
    }

    private void handleInfoViewClicked() {
        final Intent intent = new Intent(this.activity, SignInInfoActivity.class);
        this.activity.startActivity(intent);
    }

    private void disableSignIn(final String string) {
        if (this.activity == null) return;
        final Button signIn = this.activity.getBinding().signIn;
        signIn.setText(string);
        signIn.setBackgroundResource(R.drawable.background_with_radius_disabled);
        signIn.setEnabled(false);
    }

    private void handleSignInClicked() {
        if (this.activity == null) return;
        final List<String> approvedWords = this.activity.getBinding().passphraseInputView.getApprovedWordList();
        if (approvedWords.size() != PASSPHRASE_LENGTH) {
            showToast(R.string.sign_in_length_error_message);
            return;
        }

        final Joiner joiner = Joiner.on(" ");
        final String masterSeed = joiner.join(approvedWords);
        tryCreateWallet(masterSeed);
    }

    private void tryCreateWallet(final String masterSeed) {
        if (this.onGoingTask) return;
        startLoadingTask();

        final Subscription sub =
                new HDWallet()
                .createFromMasterSeed(masterSeed)
                .flatMapCompletable(this::initWallet)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnCompleted(SharedPrefsUtil::setHasBackedUpPhrase)
                .subscribe(
                        this::handleWalletSuccess,
                        this::handleWalletError
                );

        this.subscriptions.add(sub);
    }

    private void startLoadingTask() {
        this.onGoingTask = true;
        this.activity.getBinding().loadingSpinner.setVisibility(View.VISIBLE);
    }

    private Completable initWallet(final HDWallet wallet) {
        return BaseApplication
                .get()
                .getToshiManager()
                .init(wallet);
    }

    private void handleWalletSuccess() {
        stopLoadingTask();
        goToMainActivity();
    }

    private void goToMainActivity() {
        SharedPrefsUtil.setSignedIn();
        final Intent intent = new Intent(this.activity, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        this.activity.startActivity(intent);
        ActivityCompat.finishAffinity(this.activity);
    }

    private void handleWalletError(final Throwable throwable) {
        LogUtil.e(getClass(), "Unable to restore wallet " + throwable.toString());
        showToast(R.string.unable_to_restore_wallet);
        stopLoadingTask();
    }

    private void stopLoadingTask() {
        this.onGoingTask = false;
        this.activity.getBinding().loadingSpinner.setVisibility(View.GONE);
    }

    private void showToast(final @StringRes int stringId) {
        Toast.makeText(
                this.activity,
                this.activity.getString(stringId),
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    public void onViewDetached() {
        this.subscriptions.clear();
        this.activity = null;
    }

    @Override
    public void onDestroyed() {
        this.subscriptions = null;
        this.activity = null;
    }
}