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

package com.toshi.view.activity;

import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.annotation.NonNull;

import com.toshi.R;
import com.toshi.databinding.ActivityChatBinding;
import com.toshi.exception.PermissionException;
import com.toshi.model.local.ActivityResultHolder;
import com.toshi.model.local.PermissionResultHolder;
import com.toshi.presenter.LoaderIds;
import com.toshi.presenter.chat.ChatPresenter;
import com.toshi.presenter.factory.ChatPresenterFactory;
import com.toshi.presenter.factory.PresenterFactory;
import com.toshi.util.LogUtil;

public final class ChatActivity extends BasePresenterActivity<ChatPresenter, ChatActivity> {

    public static final String EXTRA__THREAD_ID = "remote_user_owner_address";
    public static final String EXTRA__PAYMENT_ACTION = "payment_action";
    public static final String EXTRA__ETH_AMOUNT = "eth_amount";
    public static final String EXTRA__PLAY_SCAN_SOUNDS = "play_scan_sounds";
    public static final String EXTRA__INIT_MESSAGE = "init_message";

    private ActivityChatBinding binding;
    private ActivityResultHolder resultHolder;
    private PermissionResultHolder permissionResultHolder;
    private ChatPresenter presenter;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init();
    }

    @Override
    public void onResume() {
        super.onResume();
        tryProcessResultHolder();
        tryProcessPermissionResultHolder();
    }

    private void init() {
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_chat);
    }

    public final ActivityChatBinding getBinding() {
        return this.binding;
    }

    @NonNull
    @Override
    protected PresenterFactory<ChatPresenter> getPresenterFactory() {
        return new ChatPresenterFactory();
    }

    @Override
    protected void onPresenterPrepared(@NonNull final ChatPresenter presenter) {
        this.presenter = presenter;
        tryProcessResultHolder();
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        this.resultHolder = new ActivityResultHolder(requestCode, resultCode, data);
        tryProcessResultHolder();
    }

    private void tryProcessResultHolder() {
        if (this.presenter == null || this.resultHolder == null) return;

        if (this.presenter.handleActivityResult(this.resultHolder)) {
            this.resultHolder = null;
        }
    }

    private void tryProcessPermissionResultHolder() {
        if (this.presenter == null || this.permissionResultHolder == null) return;

        try {
            final boolean isPermissionHandled = this.presenter.tryHandlePermissionResult(this.permissionResultHolder);
            if (isPermissionHandled) {
                this.permissionResultHolder = null;
            }
        } catch (PermissionException e) {
            LogUtil.e(getClass(), "Error during permission request");
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
                                           @NonNull final String permissions[],
                                           @NonNull final int[] grantResults) {
        this.permissionResultHolder = new PermissionResultHolder(requestCode, permissions, grantResults);
        tryProcessPermissionResultHolder();
    }

    @Override
    protected int loaderId() {
        return LoaderIds.get(this.getClass().getCanonicalName());
    }

    @Override
    public void onSaveInstanceState(final Bundle outState) {
        this.presenter.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onRestoreInstanceState(final Bundle savedInstanceState) {
        this.presenter.onRestoreInstanceState(savedInstanceState);
    }


}
