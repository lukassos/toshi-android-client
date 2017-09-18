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

package com.toshi.manager.chat;


import android.content.SharedPreferences;

import com.toshi.crypto.signal.ChatService;
import com.toshi.crypto.signal.SignalPreferences;
import com.toshi.crypto.signal.store.ProtocolStore;
import com.toshi.manager.network.IdService;
import com.toshi.model.local.Recipient;
import com.toshi.model.local.User;
import com.toshi.model.network.UserSearchResults;
import com.toshi.util.GcmUtil;
import com.toshi.util.LogUtil;
import com.toshi.util.SharedPrefsUtil;
import com.toshi.view.BaseApplication;

import org.whispersystems.libsignal.util.guava.Optional;

import java.io.IOException;

import rx.Completable;
import rx.Single;
import rx.schedulers.Schedulers;

public class SofaMessageRegistration {

    private static final String ONBOARDING_BOT_NAME = "spambot7777";
    public static final String ONBOARDING_BOT_ID = "0x8f9bdb7f562ccdedf3c24cf25e9cece9df62138b";
    private static final String CHAT_SERVICE_SENT_TOKEN_TO_SERVER = "chatServiceSentTokenToServer";

    private final SharedPreferences sharedPreferences;
    private final ChatService chatService;
    private final ProtocolStore protocolStore;
    private String gcmToken;

    public SofaMessageRegistration(
            final SharedPreferences sharedPreferences,
            final ChatService chatService,
            final ProtocolStore protocolStore) {
        this.sharedPreferences = sharedPreferences;
        this.chatService = chatService;
        this.protocolStore = protocolStore;

        if (this.sharedPreferences == null || this.chatService == null || this.protocolStore == null) {
            throw new NullPointerException("Initialised with null");
        }
    }

    public Completable registerIfNeeded() {
        if (!haveRegisteredWithServer()) {
            return this.chatService
                    .registerKeys(this.protocolStore)
                    .andThen(setRegisteredWithServer())
                    .andThen(registerGcm(true))
                    .andThen(tryTriggerOnboarding())
                    .toCompletable()
                    .onErrorComplete();
        } else {
            return registerGcm(false);
        }
    }

    private boolean haveRegisteredWithServer() {
        return SignalPreferences.getRegisteredWithServer();
    }

    private Completable setRegisteredWithServer() {
        return Completable.fromAction(SignalPreferences::setRegisteredWithServer);
    }

    public Completable registerGcm(final boolean forceUpdate) {
        return GcmUtil.getGcmToken()
                .flatMapCompletable(token -> registerChatServiceGcm(token, forceUpdate));
    }

    private Completable registerChatServiceGcm(final String token, final boolean forceUpdate) {
        final boolean sentToServer = this.sharedPreferences.getBoolean(CHAT_SERVICE_SENT_TOKEN_TO_SERVER, false);
        if (!forceUpdate && sentToServer) {
            return Completable.complete();
        }

        this.gcmToken = token;
        return tryRegisterChatGcm();
    }

    private Completable tryRegisterChatGcm() {
        return Completable.fromAction(() -> {
            final boolean isSentToSever = this.sharedPreferences.getBoolean(CHAT_SERVICE_SENT_TOKEN_TO_SERVER, false);
            if (this.gcmToken == null || isSentToSever) return;
            try {
                final Optional<String> optional = Optional.of(this.gcmToken);
                this.chatService.setGcmId(optional);
                setSentToSever(CHAT_SERVICE_SENT_TOKEN_TO_SERVER, true);
                this.gcmToken = null;
            } catch (IOException e) {
                LogUtil.exception(getClass(), "Error during registering of GCM " + e.getMessage());
                setSentToSever(CHAT_SERVICE_SENT_TOKEN_TO_SERVER, false);
                Completable.error(e);
            }
        })
        .subscribeOn(Schedulers.io());
    }

    private void setSentToSever(final String key, final boolean value) {
        this.sharedPreferences.edit().putBoolean(key, value).apply();
    }

    private Single<User> tryTriggerOnboarding() {
        if (SharedPrefsUtil.hasOnboarded()) return Single.just(null);

        return IdService.getApi()
                .searchByUsername(ONBOARDING_BOT_NAME)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .map(UserSearchResults::getResults)
                .toObservable()
                .flatMapIterable(users -> users)
                .filter(user -> user.getUsernameForEditing().equals(ONBOARDING_BOT_NAME))
                .toSingle()
                .doOnSuccess(__ -> SharedPrefsUtil.setHasOnboarded(true))
                .doOnSuccess(this::sendOnboardingMessageToOnboardingBot)
                .doOnError(throwable -> LogUtil.exception(getClass(), "Error during sending onboarding message to bot", throwable))
                .onErrorReturn(__ -> null);
    }

    private void sendOnboardingMessageToOnboardingBot(final User onboardingBot) {
        if (onboardingBot == null) return;
        BaseApplication
                .get()
                .getSofaMessageManager()
                .sendInitMessage(new Recipient(onboardingBot));
    }

    public Completable tryUnregisterGcm() {
        return Completable.fromAction(() -> {
            try {
                this.chatService.setGcmId(Optional.absent());
                this.sharedPreferences.edit().putBoolean(CHAT_SERVICE_SENT_TOKEN_TO_SERVER, false).apply();
            } catch (IOException e) {
                LogUtil.d(getClass(), "Error during unregistering of GCM " + e.getMessage());
            }
        })
        .subscribeOn(Schedulers.io());
    }
}
