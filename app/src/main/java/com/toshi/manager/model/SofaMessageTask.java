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

package com.toshi.manager.model;


import android.support.annotation.IntDef;

import com.toshi.model.local.Recipient;
import com.toshi.model.sofa.SofaMessage;

public final class SofaMessageTask {

    @IntDef({SEND_AND_SAVE, SAVE_ONLY, SEND_ONLY, UPDATE_MESSAGE, SAVE_TRANSACTION, SEND_INIT})
    public @interface Action {}
    public static final int SEND_AND_SAVE = 0;
    public static final int SAVE_ONLY = 1;
    public static final int SEND_ONLY = 2;
    public static final int UPDATE_MESSAGE = 3;
    public static final int SAVE_TRANSACTION = 4;
    public static final int SEND_INIT = 5;

    private final Recipient receiver;
    private final SofaMessage sofaMessage;
    private final @Action int action;

    public SofaMessageTask(
            final Recipient receiver,
            final SofaMessage sofaMessage,
            final @Action int action) {
        this.receiver = receiver;
        this.sofaMessage = sofaMessage;
        this.action = action;
    }

    public boolean isGroup() {
        return this.receiver.isGroup();
    }

    public Recipient getReceiver() {
        return receiver;
    }

    public SofaMessage getSofaMessage() {
        return sofaMessage;
    }

    public int getAction() {
        return action;
    }
}
