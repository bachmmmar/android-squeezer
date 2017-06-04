/*
 * Copyright (c) 2016 KKurt Aaholst <kaaholst@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.org.ngo.squeezer.service;

import android.util.Log;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.HashMapMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import uk.org.ngo.squeezer.BuildConfig;

/**
 * {@link BayeuxClient} implementation for the Squeezer App.
 * <p>
 * This is responsible for logging and to work around an id problem.
 * <p>
 * The standard {@link BayeuxClient} set a message id in the request and expect the server to echo
 * it, in the response.
 * <p>
 * LMS doesn't do include the message id in all responses (it is not a required field in the spec),
 * so we intercept outgoing and incoming messages, note the message id from the request, and add it
 * to the response.
 */
class SqueezerBayeuxClient extends BayeuxClient {
    private static final String TAG = SqueezerBayeuxClient.class.getSimpleName();

    private Map<Object, String> subscriptionIds = new HashMap<>();

    SqueezerBayeuxClient(String url, ClientTransport transport, ClientTransport... transports) {
        super(url, transport, transports);
    }

    @Override
    public void onFailure(Throwable failure, List<? extends Message> messages) {
        super.onFailure(failure, messages);
        Log.w(TAG, "onFailure(" + messages + ")", failure);
    }

    @Override
    public void onSending(List<? extends Message> messages) {
        super.onSending(messages);
        for (Message message : messages) {
            if (Channel.META_SUBSCRIBE.equals(message.getChannel())) {
                subscriptionIds.put(message.get(Message.SUBSCRIPTION_FIELD), message.getId());
            }
            if (BuildConfig.DEBUG) {
                Log.v(TAG, "SEND: " + message.getJSON());
            }
        }
    }

    @Override
    public void onMessages(List<Message.Mutable> messages) {
        super.onMessages(messages);
        for (Message message : messages) {
            if (message.getId() == null && message instanceof HashMapMessage) {
                if (Channel.META_SUBSCRIBE.equals(message.getChannel())) {
                    Object key = message.get(Message.SUBSCRIPTION_FIELD);
                    ((HashMapMessage) message).setId(subscriptionIds.get(key));
                    subscriptionIds.remove(key);
                }
            }
            if (BuildConfig.DEBUG) {
                Log.v(TAG, "RECV: " + message.getJSON());
            }
        }
    }
}