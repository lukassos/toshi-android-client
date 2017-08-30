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
import android.view.View;
import android.widget.Toast;

import com.toshi.R;
import com.toshi.util.LogUtil;
import com.toshi.util.SharedPrefsUtil;
import com.toshi.util.TermsDialog;
import com.toshi.view.BaseApplication;
import com.toshi.view.activity.LandingActivity;
import com.toshi.view.activity.MainActivity;
import com.toshi.view.activity.SignInActivity;

import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

public class LandingPresenter implements Presenter<LandingActivity> {

    private LandingActivity activity;
    private boolean firstTimeAttaching = true;
    private CompositeSubscription subscriptions;
    private boolean onGoingTask = false;

    @Override
    public void onViewAttached(LandingActivity view) {
        this.activity = view;

        if (this.firstTimeAttaching) {
            this.firstTimeAttaching = false;
            initLongLivingObjects();
        }

        initClickListeners();
    }

    private void initLongLivingObjects() {
        this.subscriptions = new CompositeSubscription();
    }

    private void initClickListeners() {
        this.activity.getBinding().signIn.setOnClickListener(__ -> goToSignInActivity());
        this.activity.getBinding().createNewAccount.setOnClickListener(__ -> showsTermDialog());
    }

    private void goToSignInActivity() {
        final Intent intent = new Intent(this.activity, SignInActivity.class);
        this.activity.startActivity(intent);
    }

    private void showsTermDialog() {
        final TermsDialog termsDialog = new TermsDialog(
                this.activity,
                __ -> handleCreateNewAccountClicked()
        );
        termsDialog.show();
    }

    private void handleCreateNewAccountClicked() {
        if (this.onGoingTask) return;
        startLoadingTask();

        final Subscription sub =
                BaseApplication
                .get()
                .getToshiManager()
                .initNewWallet()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
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

    private void handleWalletSuccess() {
        stopLoadingTask();
        goToMainActivity();
    }

    private void goToMainActivity() {
        SharedPrefsUtil.setSignedIn();
        final Intent intent = new Intent(this.activity, MainActivity.class);
        this.activity.startActivity(intent);
        this.activity.finish();
    }

    private void handleWalletError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error while creating new wallet", throwable);
        stopLoadingTask();
        showToast(R.string.unable_to_create_wallet);
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
