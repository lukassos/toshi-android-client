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

package com.toshi.service;

import android.app.IntentService;
import android.content.Intent;

import com.toshi.view.BaseApplication;

public class RegistrationIntentService extends IntentService {

    public static final String FORCE_UPDATE = "update_token";
    public static final String ETH_REGISTRATION_ONLY = "eth_registration_only";

    public RegistrationIntentService() {
        super("RegIntentService");
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        final boolean forceUpdate = intent.getBooleanExtra(FORCE_UPDATE, false);
        final boolean ethRegistrationOnly = intent.getBooleanExtra(ETH_REGISTRATION_ONLY, false);
        if (ethRegistrationOnly) {
            BaseApplication
                    .get()
                    .getBalanceManager()
                    .registerGcm(forceUpdate);
            return;
        }

        BaseApplication
                .get()
                .getSofaMessageManager()
                .registerGcm(forceUpdate);
    }
}
