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


import android.support.annotation.NonNull;

import com.toshi.BuildConfig;
import com.toshi.crypto.HDWallet;
import com.toshi.crypto.signal.store.ProtocolStore;
import com.toshi.exception.GroupCreationException;
import com.toshi.manager.model.SofaMessageTask;
import com.toshi.manager.store.ConversationStore;
import com.toshi.manager.store.PendingMessageStore;
import com.toshi.model.local.Group;
import com.toshi.model.local.PendingMessage;
import com.toshi.model.local.Recipient;
import com.toshi.model.local.SendState;
import com.toshi.model.sofa.OutgoingAttachment;
import com.toshi.model.sofa.SofaMessage;
import com.toshi.util.LogUtil;
import com.toshi.view.BaseApplication;

import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.util.Hex;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.EncapsulatedExceptions;
import org.whispersystems.signalservice.internal.push.SignalServiceUrl;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

import rx.Single;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;

import static com.toshi.util.FileUtil.buildSignalServiceAttachment;

public class SofaMessageSender {

    private final static String USER_AGENT = "Android " + BuildConfig.APPLICATION_ID + " - " + BuildConfig.VERSION_NAME +  ":" + BuildConfig.VERSION_CODE;

    private final CompositeSubscription subscriptions;
    private final ConversationStore conversationStore;
    private final HDWallet wallet;
    private final PendingMessageStore pendingMessageStore;
    private final ProtocolStore protocolStore;
    private final PublishSubject<SofaMessageTask> messageQueue;
    private final SignalServiceMessageSender signalMessageSender;


    public SofaMessageSender(@NonNull final HDWallet wallet,
                             @NonNull final ProtocolStore protocolStore,
                             @NonNull final ConversationStore conversationStore,
                             @NonNull final SignalServiceUrl[] urls) {
        this.conversationStore = conversationStore;
        this.messageQueue = PublishSubject.create();
        this.pendingMessageStore = new PendingMessageStore();
        this.protocolStore = protocolStore;
        this.subscriptions = new CompositeSubscription();
        this.wallet = wallet;

        this.signalMessageSender =
                new SignalServiceMessageSender(
                        urls,
                        this.wallet.getOwnerAddress(),
                        this.protocolStore.getPassword(),
                        this.protocolStore,
                        USER_AGENT,
                        Optional.absent(),
                        Optional.absent()
                );

        attachSubscriber();
    }

    private void attachSubscriber() {
        final Subscription sub =
                this.messageQueue
                        .observeOn(Schedulers.io())
                        .subscribeOn(Schedulers.io())
                        .subscribe(
                                this::processTask,
                                this::handleMessageError
                        );
        this.subscriptions.add(sub);
    }

    private void processTask(final SofaMessageTask messageTask) {
        switch (messageTask.getAction()) {
            case SofaMessageTask.SEND_AND_SAVE:
                sendMessageToRecipient(messageTask, true);
                break;
            case SofaMessageTask.SAVE_ONLY:
                storeMessage(messageTask.getReceiver(), messageTask.getSofaMessage(), SendState.STATE_LOCAL_ONLY);
                break;
            case SofaMessageTask.SAVE_TRANSACTION:
                storeMessage(messageTask.getReceiver(), messageTask.getSofaMessage(), SendState.STATE_SENDING);
                break;
            case SofaMessageTask.SEND_ONLY:
                sendMessageToRecipient(messageTask, false);
                break;
            case SofaMessageTask.UPDATE_MESSAGE:
                updateExistingMessage(messageTask.getReceiver(), messageTask.getSofaMessage());
                break;
            case SofaMessageTask.SEND_INIT:
                sendInitMessageToBot(messageTask);
                break;
        }
    }

    private void handleMessageError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Message sending/receiving now broken due to this error", throwable);
    }

    public void addNewTask(final SofaMessageTask messageTask) {
        this.messageQueue.onNext(messageTask);
    }

    public void sendPendingMessage(final SofaMessage sofaMessage) {
        Single.fromCallable(() -> this.pendingMessageStore.fetchPendingMessage(sofaMessage))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::handlePendingMessage);
    }

    private void handlePendingMessage(final PendingMessage pendingMessage) {
        if (pendingMessage == null) return;

        final SofaMessageTask messageTask = new SofaMessageTask(
                pendingMessage.getReceiver(),
                pendingMessage.getSofaMessage(),
                SofaMessageTask.SEND_AND_SAVE);
        addNewTask(messageTask);
    }

    public Single<Group> createGroup(final Group group) {
        return Single.fromCallable(() -> {
            try {
                final SignalServiceGroup signalGroup = new SignalServiceGroup(
                        SignalServiceGroup.Type.UPDATE,
                        Hex.fromStringCondensed(group.getId()),
                        group.getTitle(),
                        group.getMemberIds(),
                        group.getAvatar().getStream());
                final SignalServiceDataMessage groupDataMessage = new SignalServiceDataMessage(System.currentTimeMillis(), signalGroup, null, null);

                this.signalMessageSender.sendMessage(group.getMemberAddresses(), groupDataMessage);
                return group;
            } catch (final IOException | EncapsulatedExceptions ex) {
                throw new GroupCreationException(ex);
            }
        });
    }

    private void sendMessageToRecipient(final SofaMessageTask messageTask, final boolean saveMessageToDatabase) {
        final Recipient receiver = messageTask.getReceiver();
        if (receiver.isGroup()) {
            sendMessageToGroup(messageTask, saveMessageToDatabase);
        } else {
            sendMessageToUser(messageTask, saveMessageToDatabase);
        }
    }

    private void sendMessageToGroup(final SofaMessageTask messageTask, final boolean saveMessageToDatabase) {
        final Recipient receiver = messageTask.getReceiver();
        final SofaMessage message = messageTask.getSofaMessage();

        if (saveMessageToDatabase) {
            this.conversationStore.saveNewMessage(receiver, message);
        }

        if (!BaseApplication.get().isConnected() && saveMessageToDatabase) {
            message.setSendState(SendState.STATE_PENDING);
            updateExistingMessage(receiver, message);
            savePendingMessage(receiver, message);
            return;
        }

        try {
            sendToSignal(receiver.getGroup().getMemberAddresses(), messageTask);

            if (saveMessageToDatabase) {
                message.setSendState(SendState.STATE_SENT);
                updateExistingMessage(receiver, message);
            }
        } catch (final IOException ex) {
            LogUtil.error(getClass(), ex.toString());
            if (saveMessageToDatabase) {
                message.setSendState(SendState.STATE_FAILED);
                updateExistingMessage(receiver, message);
            }
        } catch (final EncapsulatedExceptions e) {
            for (UntrustedIdentityException uie : e.getUntrustedIdentityExceptions()) {
                LogUtil.error(getClass(), "Keys have changed.");
                protocolStore.saveIdentity(new SignalProtocolAddress(uie.getE164Number(), SignalServiceAddress.DEFAULT_DEVICE_ID), uie.getIdentityKey());
            }
        }
    }

    private void sendMessageToUser(final SofaMessageTask messageTask, final boolean saveMessageToDatabase) {
        final Recipient receiver = messageTask.getReceiver();
        final SofaMessage message = messageTask.getSofaMessage();

        if (saveMessageToDatabase) {
            this.conversationStore.saveNewMessage(receiver, message);
        }

        if (!BaseApplication.get().isConnected() && saveMessageToDatabase) {
            message.setSendState(SendState.STATE_PENDING);
            updateExistingMessage(receiver, message);
            savePendingMessage(receiver, message);
            return;
        }

        try {
            sendToSignal(receiver.getUser().getToshiId(), messageTask);

            if (saveMessageToDatabase) {
                message.setSendState(SendState.STATE_SENT);
                updateExistingMessage(receiver, message);
            }
        } catch (final UntrustedIdentityException ue) {
            LogUtil.error(getClass(), "Keys have changed. " + ue);
            protocolStore.saveIdentity(
                    new SignalProtocolAddress(receiver.getUser().getToshiId(), SignalServiceAddress.DEFAULT_DEVICE_ID),
                    ue.getIdentityKey());
        } catch (final IOException ex) {
            LogUtil.error(getClass(), ex.toString());
            if (saveMessageToDatabase) {
                message.setSendState(SendState.STATE_FAILED);
                updateExistingMessage(receiver, message);
                savePendingMessage(receiver, message);
            }
        }
    }

    private void sendInitMessageToBot(final SofaMessageTask messageTask) {
        final Recipient receiver = messageTask.getReceiver();
        try {
            sendToSignal(receiver.getUser().getToshiId(), messageTask);
        } catch (IOException e) {
            LogUtil.error(getClass(), e.toString());
        } catch (UntrustedIdentityException e) {
            LogUtil.error(getClass(), "Keys have changed. " + e);
        }
    }

    private void sendToSignal(final List<SignalServiceAddress> signalAddresses, final SofaMessageTask messageTask) throws IOException, EncapsulatedExceptions {
        final SignalServiceDataMessage message = buildMessage(messageTask);
        this.signalMessageSender.sendMessage(signalAddresses, message);
    }

    private void sendToSignal(final String signalAddress, final SofaMessageTask messageTask) throws UntrustedIdentityException, IOException {
        final SignalServiceAddress receivingAddress = new SignalServiceAddress(signalAddress);
        final SignalServiceDataMessage message = buildMessage(messageTask);
        this.signalMessageSender.sendMessage(receivingAddress, message);
    }

    private SignalServiceDataMessage buildMessage(final SofaMessageTask messageTask) throws FileNotFoundException {
        final SignalServiceDataMessage.Builder messageBuilder = SignalServiceDataMessage.newBuilder();
        messageBuilder.withBody(messageTask.getSofaMessage().getAsSofaMessage());

        tryAddAttachment(messageTask, messageBuilder);
        tryAddGroup(messageTask, messageBuilder);

        return messageBuilder.build();
    }

    private void tryAddGroup(final SofaMessageTask messageTask, final SignalServiceDataMessage.Builder messageBuilder) {
        try {
            if (!messageTask.getReceiver().isGroup()) return;
            final SignalServiceGroup signalGroup = new SignalServiceGroup(
                    Hex.fromStringCondensed(messageTask.getReceiver().getGroup().getId()));
            messageBuilder.asGroupMessage(signalGroup);
        } catch (final Exception ex) {
            LogUtil.i(getClass(), "Tried and failed to attach group." + ex);
        }

    }

    private void tryAddAttachment(final SofaMessageTask messageTask, final SignalServiceDataMessage.Builder messageBuilder) {
        try {
            final OutgoingAttachment outgoingAttachment = new OutgoingAttachment(messageTask.getSofaMessage());
            if (outgoingAttachment.isValid()) {
                final SignalServiceAttachment signalAttachment = buildSignalServiceAttachment(outgoingAttachment);
                messageBuilder.withAttachment(signalAttachment);
            }
        } catch (final FileNotFoundException | IllegalStateException ex) {
            LogUtil.i(getClass(), "Tried and failed to attach attachment." + ex);
        }
    }

    private void savePendingMessage(final Recipient receiver, final SofaMessage message) {
        this.pendingMessageStore.save(receiver, message);
    }

    private void storeMessage(
            final Recipient receiver,
            final SofaMessage message,
            final @SendState.State int sendState) {
        message.setSendState(sendState);
        this.conversationStore.saveNewMessage(receiver, message);
    }

    private void updateExistingMessage(final Recipient receiver, final SofaMessage message) {
        this.conversationStore.updateMessage(receiver, message);
    }

    public void clear() {
        this.subscriptions.clear();
    }
}
