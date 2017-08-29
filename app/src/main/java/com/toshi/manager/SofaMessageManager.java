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
import android.util.Pair;

import com.toshi.BuildConfig;
import com.toshi.R;
import com.toshi.crypto.HDWallet;
import com.toshi.crypto.signal.ChatService;
import com.toshi.crypto.signal.SignalPreferences;
import com.toshi.crypto.signal.model.DecryptedSignalMessage;
import com.toshi.crypto.signal.store.ProtocolStore;
import com.toshi.crypto.signal.store.SignalTrustStore;
import com.toshi.manager.chat.SofaMessageReceiver;
import com.toshi.manager.chat.SofaMessageRegistration;
import com.toshi.manager.chat.SofaMessageSender;
import com.toshi.manager.model.SofaMessageTask;
import com.toshi.manager.store.ConversationStore;
import com.toshi.model.local.Conversation;
import com.toshi.model.local.Group;
import com.toshi.model.local.Recipient;
import com.toshi.model.local.User;
import com.toshi.model.sofa.Init;
import com.toshi.model.sofa.SofaAdapters;
import com.toshi.model.sofa.SofaMessage;
import com.toshi.util.FileNames;
import com.toshi.util.LocaleUtil;
import com.toshi.view.BaseApplication;

import org.whispersystems.signalservice.internal.push.SignalServiceUrl;

import java.util.List;
import java.util.concurrent.TimeoutException;

import rx.Completable;
import rx.Observable;
import rx.Single;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

public final class SofaMessageManager {
    private final ConversationStore conversationStore;

    private final SharedPreferences sharedPreferences;
    private final SignalServiceUrl[] signalServiceUrls;
    private final CompositeSubscription subscriptions;
    private final String userAgent;

    private ChatService chatService;
    private ProtocolStore protocolStore;
    private SofaMessageReceiver messageReceiver;
    private SofaMessageRegistration sofaGcmRegister;
    private SofaMessageSender messageSender;
    private HDWallet wallet;

    /*package*/ SofaMessageManager() {
        this.conversationStore = new ConversationStore();
        this.userAgent = "Android " + BuildConfig.APPLICATION_ID + " - " + BuildConfig.VERSION_NAME +  ":" + BuildConfig.VERSION_CODE;
        this.signalServiceUrls = new SignalServiceUrl[1];
        this.sharedPreferences = BaseApplication.get().getSharedPreferences(FileNames.GCM_PREFS, Context.MODE_PRIVATE);
        this.subscriptions = new CompositeSubscription();
    }

    public final Completable init(final HDWallet wallet) {
        this.wallet = wallet;
        return initEverything();
    }

    // Will send the message to a remote peer
    // and store the message in the local database
    public final void sendAndSaveMessage(final Recipient receiver, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(receiver, message, SofaMessageTask.SEND_AND_SAVE);
        this.messageSender.addNewTask(messageTask);
    }

    // Will send the message to a remote peer
    // but not store the message in the local database
    public final void sendMessage(final Recipient recipient, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(recipient, message, SofaMessageTask.SEND_ONLY);
        this.messageSender.addNewTask(messageTask);
    }

    // Will send an init message to remote peer
    public final void sendInitMessage(final Recipient recipient) {
        final User user = recipient.getUser();
        final Init initMessage = new Init().construct(
                user.getPaymentAddress(),
                LocaleUtil.getLocale().getLanguage()
        );
        final String messageBody = SofaAdapters.get().toJson(initMessage);
        final SofaMessage sofaMessage = new SofaMessage().makeNew(user, messageBody);
        final SofaMessageTask messageTask = new SofaMessageTask(recipient, sofaMessage, SofaMessageTask.SEND_INIT);
        this.messageSender.addNewTask(messageTask);
    }

    // Create a new group
    public final Single<Group> createGroup(final Group group) {
        return
                this.messageSender.createGroup(group)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .doOnSuccess(this.conversationStore::saveNewGroup);
    }

    // Will store a transaction in the local database
    // but not send the message to a remote peer. It will also save the state as "SENDING".
    /* package */ final void saveTransaction(final User user, final SofaMessage message) {
        final Recipient recipient = new Recipient(user);
        final SofaMessageTask messageTask = new SofaMessageTask(recipient, message, SofaMessageTask.SAVE_TRANSACTION);
        this.messageSender.addNewTask(messageTask);
    }

    // Updates a pre-existing message.
    /* package */ final void updateMessage(final Recipient recipient, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(recipient, message, SofaMessageTask.UPDATE_MESSAGE);
        this.messageSender.addNewTask(messageTask);
    }

    public final void resumeMessageReceiving() {
        if (haveRegisteredWithServer() && this.wallet != null && this.messageReceiver != null) {
            this.messageReceiver.receiveMessagesAsync();
        }
    }

    private boolean haveRegisteredWithServer() {
        return SignalPreferences.getRegisteredWithServer();
    }

    public final void disconnect() {
        if (this.messageReceiver != null) {
            this.messageReceiver.shutdown();
        }
    }

    public final Single<List<Conversation>> loadAllConversations() {
        return Single
                .fromCallable(conversationStore::loadAll)
                .subscribeOn(Schedulers.io());
    }

    public final Single<Conversation> loadConversation(final String threadId) {
        return this.conversationStore.loadByThreadId(threadId)
                .subscribeOn(Schedulers.io());
    }

    public Completable deleteConversation(final Conversation conversation) {
        return this.conversationStore
                .deleteByThreadId(conversation.getThreadId())
                .subscribeOn(Schedulers.io());
    }

    public void deleteMessage(final Recipient recipient, final SofaMessage sofaMessage) {
        this.conversationStore
                .deleteMessageById(recipient, sofaMessage);
    }

    public final Observable<Conversation> registerForAllConversationChanges() {
        return this.conversationStore.getConversationChangedObservable();
    }

    // Returns a pair of RxSubjects, the first being the observable for new messages
    // the second being the observable for updated messages.
    public final Pair<PublishSubject<SofaMessage>, PublishSubject<SofaMessage>> registerForConversationChanges(final String threadId) {
        return this.conversationStore.registerForChanges(threadId);
    }

    public final Observable<SofaMessage> registerForDeletedMessages(final String threadId) {
        return this.conversationStore.registerForDeletedMessages(threadId);
    }

    public final void stopListeningForChanges() {
        this.conversationStore.stopListeningForChanges();
    }

    public final Single<Boolean> areUnreadMessages() {
        return Single
                .fromCallable(conversationStore::areUnreadMessages)
                .subscribeOn(Schedulers.io());
    }

    private Completable initEverything() {
        generateStores();
        initMessageReceiver();
        initMessageSender();
        return initRegistrationTask();
    }

    private void generateStores() {
        this.protocolStore = new ProtocolStore().init();
        final SignalTrustStore trustStore = new SignalTrustStore();

        final SignalServiceUrl signalServiceUrl = new SignalServiceUrl(
                BaseApplication.get().getResources().getString(R.string.chat_url),
                trustStore);
        this.signalServiceUrls[0] = signalServiceUrl;
        this.chatService = new ChatService(this.signalServiceUrls, this.wallet, this.protocolStore, this.userAgent);
    }

    private void initMessageReceiver() {
        if (this.messageReceiver != null) return;
        this.messageReceiver = new SofaMessageReceiver(
                this.wallet,
                this.protocolStore,
                this.conversationStore,
                this.signalServiceUrls);
    }

    private void initMessageSender() {
        if (this.messageSender != null) return;
        this.messageSender = new SofaMessageSender(
                this.wallet,
                this.protocolStore,
                this.conversationStore,
                this.signalServiceUrls
        );
    }

    private Completable initRegistrationTask() {
        if (this.sofaGcmRegister != null) return Completable.complete();
        this.sofaGcmRegister = new SofaMessageRegistration(this.sharedPreferences, this.chatService, this.protocolStore);
        return this.sofaGcmRegister
                .registerIfNeeded()
                .doOnCompleted(this::handleRegistrationCompleted);
    }

    private void handleRegistrationCompleted() {
        if (this.messageReceiver == null) return;
        this.messageReceiver.receiveMessagesAsync();
    }

    public Completable tryUnregisterGcm() {
        if (this.sofaGcmRegister == null) {
            return Completable.error(new NullPointerException("Unable to register as class hasn't been initialised yet."));
        }
        return this.sofaGcmRegister.tryUnregisterGcm();
    }

   public Completable registerGcm(final boolean forceUpdate) {
       if (this.sofaGcmRegister == null) {
           return Completable.error(new NullPointerException("Unable to register as class hasn't been initialised yet."));
       }
       return this.sofaGcmRegister.registerGcm(forceUpdate);
   }

    public void resendPendingMessage(final SofaMessage sofaMessage) {
        this.messageSender.sendPendingMessage(sofaMessage);
    }

    public DecryptedSignalMessage fetchLatestMessage() throws TimeoutException {
        try {
            while (this.messageReceiver == null) {
                Thread.sleep(200);
            }
        } catch (final InterruptedException e) {
            throw new TimeoutException(e.toString());
        }
        return this.messageReceiver.fetchLatestMessage();
    }

    public void clear() {
        clearMessageReceiver();
        clearMessageSender();
        clearGcmRegistration();
        clearSubscriptions();
        this.protocolStore.deleteAllSessions();
        this.sharedPreferences
                .edit()
                .clear()
                .apply();
    }

    private void clearMessageReceiver() {
        if (this.messageReceiver != null) {
            this.messageReceiver.shutdown();
            this.messageReceiver = null;
        }
    }

    private void clearMessageSender() {
        if (this.messageSender != null) {
            this.messageSender.clear();
            this.messageSender = null;
        }
    }

    private void clearGcmRegistration() {
        this.sofaGcmRegister = null;
    }

    private void clearSubscriptions() {
        this.subscriptions.clear();
    }
}
