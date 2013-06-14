/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.filter.query;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.mina.api.AbstractIoFilter;
import org.apache.mina.api.IoFuture;
import org.apache.mina.api.IoSession;
import org.apache.mina.filterchain.ReadFilterChainController;
import org.apache.mina.session.AttributeKey;

/**
 * A filter providing {@link IoFuture} for request/response protocol.
 * 
 * You send a request to the connected end-point and a {@link IoFuture} is provided for handling the received request
 * response.
 * 
 * The filter find the received message matching the request, using {@link Request#requestId()} and
 * {@link Response#requestId()}.
 * 
 * <pre>
 * RequestFilter rq = new RequestFilter();
 * 
 * service.setFilters(.., rq);
 * 
 * IoFuture&lt;Response&gt; future = rq.request(session, message, 10000);
 * 
 * response.register(new AbstractIoFutureListener&lt;Response&gt;() {
 *     &#064;Override
 *     public void completed(Response result) {
 *         System.err.println(&quot;request completed ! response : &quot; + result);
 *     }
 * });
 * </pre>
 * 
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class RequestFilter<REQUEST extends Request, RESPONSE extends Response> extends AbstractIoFilter {

    /**
     * 
     * @param session
     * @param request
     * @param timeoutInMs
     * @return
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public IoFuture<RESPONSE> request(IoSession session, REQUEST request, long timeoutInMs) {
        Map inFlight = session.getAttribute(IN_FLIGHT_REQUESTS);
        IoFuture<RESPONSE> future = new RequestFuture<REQUEST, RESPONSE>(session, System.currentTimeMillis()
                + timeoutInMs, request.requestId());

        // save the future for completion
        inFlight.put(request.requestId(), future);
        session.write(request);
        return future;
    }

    @SuppressWarnings("rawtypes")
    static final AttributeKey<Map> IN_FLIGHT_REQUESTS = new AttributeKey<Map>(Map.class, "request.in.flight");

    // last time we checked the timeouts
    private long lastTimeoutCheck = 0;

    @SuppressWarnings("rawtypes")
    @Override
    public void sessionOpened(IoSession session) {
        session.setAttribute(IN_FLIGHT_REQUESTS, new ConcurrentHashMap());
    }

    @SuppressWarnings("unchecked")
    @Override
    public void messageReceived(IoSession session, Object message, ReadFilterChainController controller) {
        if (message instanceof Response) {
            Object id = ((Response) message).requestId();
            if (id != null) {
                // got a response, let's find the query
                Map<?, ?> inFlight = session.getAttribute(IN_FLIGHT_REQUESTS);
                RequestFuture<REQUEST, RESPONSE> future = (RequestFuture<REQUEST, RESPONSE>) inFlight.remove(id);
                if (future != null) {
                    future.complete((RESPONSE) message);
                }
            }
        }

        // check for timeout
        long now = System.currentTimeMillis();
        if (lastTimeoutCheck + 1000 < now) {
            lastTimeoutCheck = now;
            Map<?, ?> inFlight = session.getAttribute(IN_FLIGHT_REQUESTS);
            for (Object v : inFlight.values()) {
                ((RequestFuture<?, ?>) v).timeoutIfNeeded(now);
            }
        }
        // trigger the next filter
        super.messageReceived(session, message, controller);
    }

    @Override
    public void messageSent(IoSession session, Object message) {
        // check for timeout
        long now = System.currentTimeMillis();
        if (lastTimeoutCheck + 1000 < now) {
            lastTimeoutCheck = now;
            Map<?, ?> inFlight = session.getAttribute(IN_FLIGHT_REQUESTS);
            for (Object v : inFlight.values()) {
                ((RequestFuture<?, ?>) v).timeoutIfNeeded(now);
            }
        }
    }

    /**
     * {@inheritDoc} cancel remaining requests
     */
    @Override
    public void sessionClosed(IoSession session) {
        Map<?, ?> inFlight = session.getAttribute(IN_FLIGHT_REQUESTS);
        for (Object v : inFlight.values()) {
            ((RequestFuture<?, ?>) v).cancel(true);
        }
    }
}