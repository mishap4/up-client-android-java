/*
 * Copyright (c) 2024 General Motors GTO LLC
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * SPDX-FileType: SOURCE
 * SPDX-FileCopyrightText: 2023 General Motors GTO LLC
 * SPDX-License-Identifier: Apache-2.0
 */
package org.eclipse.uprotocol;

import static org.eclipse.uprotocol.client.BuildConfig.LIBRARY_PACKAGE_NAME;
import static org.eclipse.uprotocol.client.BuildConfig.VERSION_NAME;
import static org.eclipse.uprotocol.common.util.UStatusUtils.STATUS_OK;
import static org.eclipse.uprotocol.common.util.UStatusUtils.buildStatus;
import static org.eclipse.uprotocol.common.util.UStatusUtils.toStatus;
import static org.eclipse.uprotocol.transport.builder.UPayloadBuilder.packToAny;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.protobuf.Int32Value;

import org.eclipse.uprotocol.UPClient.ServiceLifecycleListener;
import org.eclipse.uprotocol.core.ubus.UBusManager;
import org.eclipse.uprotocol.transport.UListener;
import org.eclipse.uprotocol.transport.builder.UAttributesBuilder;
import org.eclipse.uprotocol.transport.validate.UAttributesValidator;
import org.eclipse.uprotocol.v1.CallOptions;
import org.eclipse.uprotocol.v1.UAttributes;
import org.eclipse.uprotocol.v1.UCode;
import org.eclipse.uprotocol.v1.UEntity;
import org.eclipse.uprotocol.v1.UMessage;
import org.eclipse.uprotocol.v1.UMessageType;
import org.eclipse.uprotocol.v1.UPayload;
import org.eclipse.uprotocol.v1.UPriority;
import org.eclipse.uprotocol.v1.UStatus;
import org.eclipse.uprotocol.v1.UUri;
import org.eclipse.uprotocol.validation.ValidationResult;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
public class UPClientTest extends TestBase {
    private static final UMessage MESSAGE = buildMessage(PAYLOAD, buildPublishAttributes(RESOURCE_URI));
    private static final UPayload REQUEST_PAYLOAD = packToAny(Int32Value.newBuilder().setValue(1).build());
    private static final UPayload RESPONSE_PAYLOAD = packToAny(STATUS_OK);

    private Context mContext;
    private String mPackageName;
    private ShadowPackageManager mShadowPackageManager;
    private Handler mHandler;
    private Executor mExecutor;
    private ServiceLifecycleListener mServiceLifecycleListener;
    private UListener mListener;
    private UListener mListener2;
    private UBusManager mManager;
    private UPClient mClient;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        mPackageName = mContext.getPackageName();
        mShadowPackageManager = Shadows.shadowOf(mContext.getPackageManager());
        mHandler = newMockHandler();
        mExecutor = newMockExecutor();
        mServiceLifecycleListener = mock(ServiceLifecycleListener.class);
        mListener = mock(UListener.class);
        mListener2 = mock(UListener.class);
        mManager = mock(UBusManager.class);
        injectPackage(buildPackageInfo(mPackageName, buildMetadata(CLIENT)));
        mClient = new UPClient(mContext, CLIENT, mManager, mExecutor, mServiceLifecycleListener);
        mClient.setLoggable(Log.INFO);
    }

    private void injectPackage(@NonNull PackageInfo packageInfo) {
        mShadowPackageManager.installPackage(packageInfo);
    }

    private static void redirectMessages(@NonNull UBusManager manager, @NonNull UPClient client) {
        doAnswer(invocation -> {
            client.getListener().onReceive(invocation.getArgument(0));
            return STATUS_OK;
        }).when(manager).send(any());
    }

    @Test
    public void testConstants() {
        assertEquals("uprotocol.permission.ACCESS_UBUS", UPClient.PERMISSION_ACCESS_UBUS);
        assertEquals("uprotocol.entity.name", UPClient.META_DATA_ENTITY_NAME);
        assertEquals("uprotocol.entity.version", UPClient.META_DATA_ENTITY_VERSION);
    }

    @Test
    public void testCreate() {
        injectPackage(buildPackageInfo(mPackageName,
                buildServiceInfo(new ComponentName(mPackageName, ".Service"), buildMetadata(SERVICE))));
        assertNotNull(UPClient.create(mContext, SERVICE, mExecutor, mServiceLifecycleListener));
    }

    @Test
    public void testCreateWithoutEntity() {
        assertNotNull(UPClient.create(mContext, mExecutor, mServiceLifecycleListener));
    }

    @Test
    public void testCreateWithEntityId() {
        final UEntity entity = UEntity.newBuilder()
                .setName(CLIENT.getName())
                .setVersionMajor(CLIENT.getVersionMajor())
                .setId(100)
                .build();
        injectPackage(buildPackageInfo(mPackageName, buildMetadata(entity)));
        assertNotNull(UPClient.create(mContext, entity, mExecutor, mServiceLifecycleListener));
    }

    @Test
    public void testCreateWitHandler() {
        assertNotNull(UPClient.create(mContext, mHandler, mServiceLifecycleListener));
    }

    @Test
    public void testCreateWithDefaultCallbackThread() {
        assertNotNull(UPClient.create(mContext, (Handler) null, mServiceLifecycleListener));
        assertNotNull(UPClient.create(mContext, (Executor) null, mServiceLifecycleListener));
    }

    @Test
    public void testCreateWithoutServiceLifecycleListener() {
        assertNotNull(UPClient.create(mContext, mExecutor, null));
    }

    @Test
    public void testCreateWithBadContextWrapper() {
        final ContextWrapper context = spy(new ContextWrapper(mContext));
        doReturn(null).when(context).getBaseContext();
        assertThrows(NullPointerException.class, () -> UPClient.create(context, mExecutor, mServiceLifecycleListener));
    }

    @Test
    public void testCreatePackageManagerNotAvailable() {
        assertThrows(NullPointerException.class, () -> UPClient.create(mock(Context.class), mExecutor, mServiceLifecycleListener));
    }

    @Test
    public void testCreatePackageNotFound() throws NameNotFoundException {
        final PackageManager manager = mock(PackageManager.class);
        doThrow(new NameNotFoundException()).when(manager).getPackageInfo(anyString(), anyInt());
        final Context context = spy(new ContextWrapper(mContext));
        doReturn(manager).when(context).getPackageManager();
        assertThrows(SecurityException.class, () -> UPClient.create(context, mExecutor, mServiceLifecycleListener));
    }

    @Test
    public void testCreateEntityNotDeclared() {
        injectPackage(buildPackageInfo(mPackageName));
        assertThrows(SecurityException.class, () -> UPClient.create(mContext, mExecutor, mServiceLifecycleListener));
    }

    @Test
    public void testCreateEntityNameNotDeclared() {
        injectPackage(buildPackageInfo(mPackageName,
                buildMetadata(UEntity.newBuilder().setVersionMajor(1).build())));
        assertThrows(SecurityException.class, () -> UPClient.create(mContext, mExecutor, mServiceLifecycleListener));
    }

    @Test
    public void testCreateEntityVersionNotDeclared() {
        injectPackage(buildPackageInfo(mPackageName,
                buildMetadata(UEntity.newBuilder().setName(CLIENT.getName()).build())));
        assertThrows(SecurityException.class, () -> UPClient.create(mContext, mExecutor, mServiceLifecycleListener));
    }

    @Test
    public void testCreateVerboseVersionLogged() {
        final String tag = mClient.getTag();
        ShadowLog.setLoggable(tag, Log.VERBOSE);
        assertNotNull(UPClient.create(mContext, mClient.getEntity(), mHandler, mServiceLifecycleListener));
        ShadowLog.getLogsForTag(tag).stream()
                .filter(it -> it.msg.contains(LIBRARY_PACKAGE_NAME) && it.msg.contains(VERSION_NAME))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Version is not printed"));
    }

    @Test
    public void testConnect() {
        final CompletableFuture<UStatus> future = new CompletableFuture<>();
        doReturn(future).when(mManager).connect();
        assertEquals(future, mClient.connect());
    }

    @Test
    public void testDisconnect() {
        final CompletableFuture<UStatus> future = new CompletableFuture<>();
        doReturn(future).when(mManager).disconnect();
        assertEquals(future, mClient.disconnect());
    }

    @Test
    public void testIsDisconnected() {
        assertFalse(mClient.isDisconnected());
        doReturn(true).when(mManager).isDisconnected();
        assertTrue(mClient.isDisconnected());
    }

    @Test
    public void testIsConnecting() {
        assertFalse(mClient.isConnecting());
        doReturn(true).when(mManager).isConnecting();
        assertTrue(mClient.isConnecting());
    }

    @Test
    public void testIsConnected() {
        assertFalse(mClient.isConnected());
        doReturn(true).when(mManager).isConnected();
        assertTrue(mClient.isConnected());
    }

    @Test
    public void testOnConnected() {
        mClient.getConnectionCallback().onConnected();
        verify(mServiceLifecycleListener, times(1)).onLifecycleChanged(mClient, true);
    }

    @Test
    public void testOnDisconnected() {
        mClient.getConnectionCallback().onDisconnected();
        verify(mServiceLifecycleListener, times(1)).onLifecycleChanged(mClient, false);
    }

    @Test
    public void testOnConnectionInterrupted() {
        mClient.getConnectionCallback().onConnectionInterrupted();
        verify(mServiceLifecycleListener, times(1)).onLifecycleChanged(mClient, false);
    }

    @Test
    public void testOnConnectedSuppressed() {
        final UPClient client = new UPClient(mContext, CLIENT, mManager, mExecutor, null);
        client.getConnectionCallback().onConnected();
        verify(mExecutor, times(1)).execute(any());
    }

    @Test
    public void testGetEntity() {
        assertEquals(CLIENT, mClient.getEntity());
    }

    @Test
    public void testGetUri() {
        assertEquals(CLIENT, mClient.getUri().getEntity());
    }

    @Test
    public void testSend() {
        doReturn(STATUS_OK).when(mManager).send(MESSAGE);
        assertStatus(UCode.OK, mClient.send(MESSAGE));
    }

    @Test
    public void testRegisterGenericListener() {
        doReturn(STATUS_OK).when(mManager).enableDispatching(RESOURCE_URI);
        assertStatus(UCode.OK, mClient.registerListener(RESOURCE_URI, mListener));
        verify(mManager, times(1)).enableDispatching(RESOURCE_URI);
        verify(mManager, never()).getLastMessage(RESOURCE_URI);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    public void testRegisterGenericListenerWithInvalidArgument() {
        assertStatus(UCode.INVALID_ARGUMENT, mClient.registerListener(UUri.getDefaultInstance(), mListener));
        assertStatus(UCode.INVALID_ARGUMENT, mClient.registerListener(null, mListener));
        assertStatus(UCode.INVALID_ARGUMENT, mClient.registerListener(RESOURCE_URI, null));
        verify(mManager, never()).enableDispatching(RESOURCE_URI);
    }

    @Test
    public void testRegisterGenericListenerDifferentTopics() {
        doReturn(STATUS_OK).when(mManager).enableDispatching(RESOURCE_URI);
        doReturn(STATUS_OK).when(mManager).enableDispatching(RESOURCE2_URI);
        assertStatus(UCode.OK, mClient.registerListener(RESOURCE_URI, mListener));
        assertStatus(UCode.OK, mClient.registerListener(RESOURCE2_URI, mListener));
        verify(mManager, times(1)).enableDispatching(RESOURCE_URI);
        verify(mManager, times(1)).enableDispatching(RESOURCE2_URI);
    }

    @Test
    public void testRegisterGenericListenerSame() {
        testRegisterGenericListener();
        assertStatus(UCode.OK, mClient.registerListener(RESOURCE_URI, mListener));
        verify(mManager, times(1)).enableDispatching(RESOURCE_URI);
        verify(mManager, never()).getLastMessage(RESOURCE_URI);
    }

    @Test
    public void testRegisterGenericListenerNotFirst() {
        testRegisterGenericListener();
        assertStatus(UCode.OK, mClient.registerListener(RESOURCE_URI, mListener2));
        verify(mManager, times(1)).enableDispatching(RESOURCE_URI);
        verify(mManager, times(1)).getLastMessage(RESOURCE_URI);
    }

    @Test
    public void testRegisterGenericListenerNotFirstLastMessageNotified() {
        doReturn(MESSAGE).when(mManager).getLastMessage(RESOURCE_URI);
        testRegisterGenericListenerNotFirst();
        verify(mListener2, timeout(DELAY_MS).times(1)).onReceive(MESSAGE);
    }

    @Test
    public void testRegisterGenericListenerFailed() {
        doReturn(buildStatus(UCode.UNAUTHENTICATED)).when(mManager).enableDispatching(RESOURCE_URI);
        assertStatus(UCode.UNAUTHENTICATED, mClient.registerListener(RESOURCE_URI, mListener));
    }

    @Test
    public void testRegisterGenericListenerWhenReconnected() {
        testRegisterGenericListener();
        mClient.getConnectionCallback().onConnectionInterrupted();
        verify(mManager, timeout(DELAY_MS).times(0)).disableDispatchingQuietly(RESOURCE_URI);
        mClient.getConnectionCallback().onConnected();
        verify(mManager, timeout(DELAY_MS).times(2)).enableDispatching(RESOURCE_URI);
    }

    @Test
    public void testUnregisterGenericListener() {
        testRegisterGenericListener();
        doReturn(STATUS_OK).when(mManager).disableDispatching(RESOURCE_URI);
        assertStatus(UCode.OK, mClient.unregisterListener(RESOURCE_URI, mListener));
        verify(mManager, times(1)).disableDispatchingQuietly(RESOURCE_URI);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    public void testUnregisterGenericListenerWithInvalidArgument() {
        assertStatus(UCode.INVALID_ARGUMENT, mClient.unregisterListener(UUri.getDefaultInstance(), mListener));
        assertStatus(UCode.INVALID_ARGUMENT, mClient.unregisterListener(null, mListener));
        assertStatus(UCode.INVALID_ARGUMENT, mClient.unregisterListener(RESOURCE_URI, null));
        verify(mManager, never()).disableDispatchingQuietly(RESOURCE_URI);
    }

    @Test
    public void testUnregisterGenericListenerSame() {
        testUnregisterGenericListener();
        assertStatus(UCode.OK, mClient.unregisterListener(RESOURCE_URI, mListener));
        verify(mManager, times(1)).disableDispatchingQuietly(RESOURCE_URI);
    }

    @Test
    public void testUnregisterGenericListenerNotRegistered() {
        testRegisterGenericListener();
        assertStatus(UCode.OK, mClient.unregisterListener(RESOURCE_URI, mListener2));
        verify(mManager, times(0)).disableDispatchingQuietly(RESOURCE_URI);
    }

    @Test
    public void testUnregisterGenericListenerNotLast() {
        testRegisterGenericListenerNotFirst();
        assertStatus(UCode.OK, mClient.unregisterListener(RESOURCE_URI, mListener));
        verify(mManager, never()).disableDispatchingQuietly(RESOURCE_URI);
    }

    @Test
    public void testUnregisterGenericListenerLast() {
        testUnregisterGenericListenerNotLast();
        assertStatus(UCode.OK, mClient.unregisterListener(RESOURCE_URI, mListener2));
        verify(mManager, times(1)).disableDispatchingQuietly(RESOURCE_URI);
    }

    @Test
    public void testUnregisterGenericListenerWhenDisconnected() {
        testRegisterGenericListener();
        mClient.getConnectionCallback().onDisconnected();
        mClient.getListener().onReceive(MESSAGE);
        verify(mListener, timeout(DELAY_MS).times(0)).onReceive(MESSAGE);
    }

    @Test
    public void testUnregisterGenericListenerFromAllTopics() {
        testRegisterGenericListenerDifferentTopics();
        assertStatus(UCode.OK, mClient.unregisterListener(mListener));
        verify(mManager, times(1)).disableDispatchingQuietly(RESOURCE_URI);
        verify(mManager, times(1)).disableDispatchingQuietly(RESOURCE2_URI);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    public void testUnregisterGenericListenerFromAllTopicsWithInvalidArgument() {
        assertStatus(UCode.INVALID_ARGUMENT, mClient.unregisterListener(null));
    }

    @Test
    public void testOnReceiveGenericMessage() {
        testRegisterGenericListenerNotFirst();
        mClient.getListener().onReceive(MESSAGE);
        verify(mListener, timeout(DELAY_MS).times(1)).onReceive(MESSAGE);
        verify(mListener2, timeout(DELAY_MS).times(1)).onReceive(MESSAGE);
    }

    @Test
    public void testOnReceiveGenericMessageNotRegistered() {
        testUnregisterGenericListener();
        mClient.getListener().onReceive(MESSAGE);
        verify(mListener, timeout(DELAY_MS).times(0)).onReceive(MESSAGE);
    }

    @Test
    public void testOnReceiveNotificationMessage() {
        testRegisterGenericListener();
        final UMessage message =
                buildMessage(PAYLOAD,newNotificationAttributesBuilder(RESOURCE_URI, CLIENT_URI).build());
        mClient.getListener().onReceive(message);
        verify(mListener, timeout(DELAY_MS).times(1)).onReceive(message);
    }

    @Test
    public void testOnReceiveNotificationMessageWrongSink() {
        mClient.setLoggable(Log.VERBOSE);
        testRegisterGenericListener();
        final UMessage message =
                buildMessage(PAYLOAD, newNotificationAttributesBuilder(RESOURCE_URI, SERVICE_URI).build());
        mClient.getListener().onReceive(message);
        verify(mListener, timeout(DELAY_MS).times(0)).onReceive(message);
    }

    @Test
    public void testOnReceiveMessageExpired() {
        mClient.setLoggable(Log.VERBOSE);
        testRegisterGenericListener();
        final UMessage message = buildMessage(PAYLOAD, newPublishAttributesBuilder(RESOURCE_URI).withTtl(1).build());
        sleep(DELAY_MS);
        mClient.getListener().onReceive(message);
        verify(mListener, timeout(DELAY_MS).times(0)).onReceive(message);
    }

    @Test
    public void testOnReceiveMessageWithoutAttributes() {
        testRegisterGenericListener();
        final UMessage message = buildMessage(null, null);
        mClient.getListener().onReceive(message);
        verify(mListener, timeout(DELAY_MS).times(0)).onReceive(message);
    }

    @Test
    public void testOnReceiveMessageWithUnknownType() {
        testRegisterGenericListener();
        try (MockedStatic<UAttributesValidator> mockedValidator = mockStatic(UAttributesValidator.class)) {
            final UMessage message = buildMessage(null, null);
            final UAttributesValidator dummyValidator = new UAttributesValidator() {
                @Override
                public ValidationResult validate(UAttributes attributes) {
                    return ValidationResult.success();
                }
                @Override
                public ValidationResult validateType(UAttributes attributes) {
                    return ValidationResult.success();
                }
            };
            mockedValidator.when(() -> UAttributesValidator.getValidator(message.getAttributes()))
                    .thenReturn(dummyValidator);
            mClient.getListener().onReceive(message);
            verify(mListener, timeout(DELAY_MS).times(0)).onReceive(message);
        }
    }

    @Test
    public void testRegisterRequestListener() {
        doReturn(STATUS_OK).when(mManager).enableDispatching(METHOD_URI);
        assertStatus(UCode.OK, mClient.registerListener(METHOD_URI, mListener));
        verify(mManager, times(1)).enableDispatching(METHOD_URI);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    public void testRegisterRequestListenerWithInvalidArgument() {
        assertStatus(UCode.INVALID_ARGUMENT, mClient.registerListener(UUri.getDefaultInstance(), mListener));
        assertStatus(UCode.INVALID_ARGUMENT, mClient.registerListener(null, mListener));
        assertStatus(UCode.INVALID_ARGUMENT, mClient.registerListener(METHOD_URI, null));
        verify(mManager, never()).enableDispatching(METHOD_URI);
    }

    @Test
    public void testRegisterRequestListenerDifferentMethods() {
        doReturn(STATUS_OK).when(mManager).enableDispatching(METHOD_URI);
        doReturn(STATUS_OK).when(mManager).enableDispatching(METHOD2_URI);
        assertStatus(UCode.OK, mClient.registerListener(METHOD_URI, mListener));
        assertStatus(UCode.OK, mClient.registerListener(METHOD2_URI, mListener));
        verify(mManager, times(1)).enableDispatching(METHOD_URI);
        verify(mManager, times(1)).enableDispatching(METHOD2_URI);
    }

    @Test
    public void testRegisterRequestListenerSame() {
        testRegisterRequestListener();
        assertStatus(UCode.OK, mClient.registerListener(METHOD_URI, mListener));
        verify(mManager, times(1)).enableDispatching(METHOD_URI);
    }

    @Test
    public void testRegisterRequestListenerNotFirst() {
        testRegisterRequestListener();
        assertStatus(UCode.ALREADY_EXISTS, mClient.registerListener(METHOD_URI, mListener2));
        verify(mManager, times(1)).enableDispatching(METHOD_URI);
    }

    @Test
    public void testRegisterRequestListenerFailed() {
        doReturn(buildStatus(UCode.UNAUTHENTICATED)).when(mManager).enableDispatching(METHOD_URI);
        assertStatus(UCode.UNAUTHENTICATED, mClient.registerListener(METHOD_URI, mListener));
    }

    @Test
    public void testRegisterRequestListenerWhenReconnected() {
        testRegisterRequestListener();
        mClient.getConnectionCallback().onConnectionInterrupted();
        verify(mManager, timeout(DELAY_MS).times(0)).disableDispatchingQuietly(METHOD_URI);
        mClient.getConnectionCallback().onConnected();
        verify(mManager, timeout(DELAY_MS).times(2)).enableDispatching(METHOD_URI);
    }

    @Test
    public void testUnregisterRequestListener() {
        testRegisterRequestListener();
        doReturn(STATUS_OK).when(mManager).disableDispatching(METHOD_URI);
        assertStatus(UCode.OK, mClient.unregisterListener(METHOD_URI, mListener));
        verify(mManager, times(1)).disableDispatchingQuietly(METHOD_URI);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    public void testUnregisterRequestListenerWithInvalidArgument() {
        assertStatus(UCode.INVALID_ARGUMENT, mClient.unregisterListener(UUri.getDefaultInstance(), mListener));
        assertStatus(UCode.INVALID_ARGUMENT, mClient.unregisterListener(null, mListener));
        assertStatus(UCode.INVALID_ARGUMENT, mClient.unregisterListener(METHOD_URI, null));
        verify(mManager, never()).disableDispatchingQuietly(METHOD_URI);
    }

    @Test
    public void testUnregisterRequestListenerSame() {
        testUnregisterRequestListener();
        assertStatus(UCode.OK, mClient.unregisterListener(METHOD_URI, mListener));
        verify(mManager, times(1)).disableDispatchingQuietly(METHOD_URI);
    }

    @Test
    public void testUnregisterRequestListenerNotRegistered() {
        testRegisterRequestListener();
        assertStatus(UCode.OK, mClient.unregisterListener(METHOD_URI, mListener2));
        verify(mManager, times(0)).disableDispatchingQuietly(METHOD_URI);
    }

    @Test
    public void testUnregisterRequestListenerWhenDisconnected() {
        testRegisterRequestListener();
        mClient.getConnectionCallback().onDisconnected();
        final UMessage requestMessage = buildMessage(PAYLOAD, buildRequestAttributes(RESPONSE_URI, METHOD_URI));
        mClient.getListener().onReceive(requestMessage);
        verify(mListener, timeout(DELAY_MS).times(0)).onReceive(eq(requestMessage));
    }

    @Test
    public void testUnregisterRequestListenerFromAllMethods() {
        testRegisterRequestListenerDifferentMethods();
        assertStatus(UCode.OK, mClient.unregisterListener(mListener));
        verify(mManager, times(1)).disableDispatchingQuietly(METHOD_URI);
        verify(mManager, times(1)).disableDispatchingQuietly(METHOD2_URI);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    public void testUnregisterRequestListenerFromAllMethodsWithInvalidArgument() {
        assertStatus(UCode.INVALID_ARGUMENT, mClient.unregisterListener(null));
    }

    @Test
    public void testUnregisterRequestListenerFromAllMethodsNotRegistered() {
        testRegisterRequestListenerDifferentMethods();
        assertStatus(UCode.OK, mClient.unregisterListener(mListener2));
        verify(mManager, times(0)).disableDispatchingQuietly(METHOD_URI);
        verify(mManager, times(0)).disableDispatchingQuietly(METHOD2_URI);
    }

    @Test
    public void testOnReceiveRequestMessage() {
        testRegisterRequestListener();
        final UMessage requestMessage = buildMessage(PAYLOAD, buildRequestAttributes(RESPONSE_URI, METHOD_URI));
        mClient.getListener().onReceive(requestMessage);
        verify(mListener, timeout(DELAY_MS).times(1)).onReceive(eq(requestMessage));
    }

    @Test
    public void testOnReceiveRequestMessageNotRegistered() {
        testUnregisterRequestListener();
        final UMessage requestMessage = buildMessage(PAYLOAD, buildRequestAttributes(RESPONSE_URI, METHOD_URI));
        mClient.getListener().onReceive(requestMessage);
        verify(mListener, timeout(DELAY_MS).times(0)).onReceive(eq(requestMessage));
    }

    @Test
    public void testInvokeMethod() throws Exception {
        testRegisterRequestListener();
        redirectMessages(mManager, mClient);

        final CompletableFuture<UMessage> responseFuture =
                mClient.invokeMethod(METHOD_URI, REQUEST_PAYLOAD, OPTIONS).toCompletableFuture();
        assertFalse(responseFuture.isDone());

        final ArgumentCaptor<UMessage> requestCaptor = ArgumentCaptor.forClass(UMessage.class);
        verify(mListener, timeout(DELAY_MS).times(1)).onReceive(requestCaptor.capture());
        final UMessage requestMessage = requestCaptor.getValue();
        final UAttributes requestAttributes = requestMessage.getAttributes();
        assertEquals(REQUEST_PAYLOAD, requestMessage.getPayload());
        assertEquals(RESPONSE_URI, requestAttributes.getSource());
        assertEquals(METHOD_URI, requestAttributes.getSink());
        assertEquals(OPTIONS.getPriority(), requestAttributes.getPriority());
        assertEquals(OPTIONS.getTtl(), requestAttributes.getTtl());
        assertEquals(OPTIONS.getToken(), requestAttributes.getToken());
        assertEquals(UMessageType.UMESSAGE_TYPE_REQUEST, requestAttributes.getType());
        mClient.send(buildMessage(RESPONSE_PAYLOAD, UAttributesBuilder.response(requestMessage.getAttributes()).build()));

        final UMessage responseMessage = responseFuture.get(DELAY_MS, TimeUnit.MILLISECONDS);
        final UAttributes responseAttributes = responseMessage.getAttributes();
        assertEquals(RESPONSE_PAYLOAD, responseMessage.getPayload());
        assertEquals(METHOD_URI, responseAttributes.getSource());
        assertEquals(RESPONSE_URI, responseAttributes.getSink());
        assertEquals(UMessageType.UMESSAGE_TYPE_RESPONSE, responseAttributes.getType());
        assertEquals(requestAttributes.getId(), responseAttributes.getReqid());
    }

    @Test
    public void testInvokeMethodWithoutToken() throws Exception {
        testRegisterRequestListener();
        redirectMessages(mManager, mClient);

        final CallOptions options = CallOptions.newBuilder(OPTIONS).clearToken().build();
        final CompletableFuture<UMessage> responseFuture =
                mClient.invokeMethod(METHOD_URI, REQUEST_PAYLOAD, options).toCompletableFuture();
        assertFalse(responseFuture.isDone());

        final ArgumentCaptor<UMessage> requestCaptor = ArgumentCaptor.forClass(UMessage.class);
        verify(mListener, timeout(DELAY_MS).times(1)).onReceive(requestCaptor.capture());
        final UMessage requestMessage = requestCaptor.getValue();
        final UAttributes requestAttributes = requestMessage.getAttributes();
        assertEquals(REQUEST_PAYLOAD, requestMessage.getPayload());
        assertEquals(RESPONSE_URI, requestAttributes.getSource());
        assertEquals(METHOD_URI, requestAttributes.getSink());
        assertEquals(options.getPriority(), requestAttributes.getPriority());
        assertEquals(options.getTtl(), requestAttributes.getTtl());
        assertEquals(options.getToken(), requestAttributes.getToken());
        assertEquals(UMessageType.UMESSAGE_TYPE_REQUEST, requestAttributes.getType());
        mClient.send(buildMessage(RESPONSE_PAYLOAD, UAttributesBuilder.response(requestMessage.getAttributes()).build()));

        final UMessage responseMessage = responseFuture.get(DELAY_MS, TimeUnit.MILLISECONDS);
        final UAttributes responseAttributes = responseMessage.getAttributes();
        assertEquals(RESPONSE_PAYLOAD, responseMessage.getPayload());
        assertEquals(METHOD_URI, responseAttributes.getSource());
        assertEquals(RESPONSE_URI, responseAttributes.getSink());
        assertEquals(UMessageType.UMESSAGE_TYPE_RESPONSE, responseAttributes.getType());
        assertEquals(requestAttributes.getId(), responseAttributes.getReqid());
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    public void testInvokeMethodWithInvalidArgument() {
        assertStatus(UCode.INVALID_ARGUMENT, toStatus(assertThrows(ExecutionException.class,
                () -> mClient.invokeMethod(null, PAYLOAD, OPTIONS).toCompletableFuture().get())));
        assertStatus(UCode.INVALID_ARGUMENT, toStatus(assertThrows(ExecutionException.class,
                () -> mClient.invokeMethod(UUri.getDefaultInstance(), PAYLOAD, OPTIONS).toCompletableFuture().get())));
        assertStatus(UCode.INVALID_ARGUMENT, toStatus(assertThrows(ExecutionException.class,
                () -> mClient.invokeMethod(METHOD_URI, null, OPTIONS).toCompletableFuture().get())));
        assertStatus(UCode.INVALID_ARGUMENT, toStatus(assertThrows(ExecutionException.class,
                () -> mClient.invokeMethod(METHOD_URI, PAYLOAD, null).toCompletableFuture().get())));
    }

    @Test
    public void testInvokeMethodWithInvalidPriority() {
        final CallOptions options = CallOptions.newBuilder(OPTIONS).setPriority(UPriority.UPRIORITY_CS0).build();
        assertStatus(UCode.INVALID_ARGUMENT, toStatus(assertThrows(ExecutionException.class,
                () -> mClient.invokeMethod(METHOD_URI, PAYLOAD, options).toCompletableFuture().get())));
    }

    @Test
    public void testInvokeMethodOtherResponseReceive() {
        testRegisterRequestListener();
        redirectMessages(mManager, mClient);

        final CompletableFuture<UMessage> responseFuture =
                mClient.invokeMethod(METHOD_URI, REQUEST_PAYLOAD, OPTIONS).toCompletableFuture();
        assertFalse(responseFuture.isDone());

        verify(mListener, timeout(DELAY_MS).times(1)).onReceive(any());
        final UMessage responseMessage =
                buildMessage(PAYLOAD, buildResponseAttributes(METHOD_URI, RESPONSE_URI, createId()));
        mClient.getListener().onReceive(responseMessage);

        assertThrows(TimeoutException.class, () -> responseFuture.get(DELAY_MS, TimeUnit.MILLISECONDS));
        assertFalse(responseFuture.isDone());
    }

    @Test
    public void testInvokeMethodWhenDisconnected() {
        testRegisterRequestListener();
        redirectMessages(mManager, mClient);

        final CompletableFuture<UMessage> responseFuture =
                mClient.invokeMethod(METHOD_URI, REQUEST_PAYLOAD, OPTIONS).toCompletableFuture();
        assertFalse(responseFuture.isDone());

        verify(mListener, timeout(DELAY_MS).times(1)).onReceive(any());
        final UMessage responseMessage =
                buildMessage(PAYLOAD, buildResponseAttributes(METHOD_URI, RESPONSE_URI, createId()));
        mClient.getListener().onReceive(responseMessage);

        testOnDisconnected();
        assertStatus(UCode.CANCELLED, toStatus(assertThrows(
                ExecutionException.class, () -> responseFuture.get(DELAY_MS, TimeUnit.MILLISECONDS))));
    }

    @Test
    public void testInvokeMethodCompletedWithCommStatus() {
        testRegisterRequestListener();
        redirectMessages(mManager, mClient);

        final CompletableFuture<UMessage> responseFuture =
                mClient.invokeMethod(METHOD_URI, REQUEST_PAYLOAD, OPTIONS).toCompletableFuture();
        assertFalse(responseFuture.isDone());

        final ArgumentCaptor<UMessage> requestCaptor = ArgumentCaptor.forClass(UMessage.class);
        verify(mListener, timeout(DELAY_MS).times(1)).onReceive(requestCaptor.capture());
        final UMessage requestMessage = requestCaptor.getValue();
        mClient.send(buildMessage(null, UAttributesBuilder
                .response(requestMessage.getAttributes())
                .withCommStatus(UCode.ABORTED)
                .build()));

        assertStatus(UCode.ABORTED, toStatus(assertThrows(
                ExecutionException.class, () -> responseFuture.get(DELAY_MS, TimeUnit.MILLISECONDS))));
    }

    @Test
    public void testInvokeMethodCompletedWithCommStatusOk() throws Exception {
        testRegisterRequestListener();
        redirectMessages(mManager, mClient);

        final CompletableFuture<UMessage> responseFuture =
                mClient.invokeMethod(METHOD_URI, REQUEST_PAYLOAD, OPTIONS).toCompletableFuture();
        assertFalse(responseFuture.isDone());

        final ArgumentCaptor<UMessage> requestCaptor = ArgumentCaptor.forClass(UMessage.class);
        verify(mListener, timeout(DELAY_MS).times(1)).onReceive(requestCaptor.capture());
        final UMessage requestMessage = requestCaptor.getValue();
        final UAttributes requestAttributes = requestMessage.getAttributes();
        mClient.send(buildMessage(null, UAttributesBuilder
                .response(requestMessage.getAttributes())
                .withCommStatus(UCode.OK)
                .build()));

        final UMessage responseMessage = responseFuture.get(DELAY_MS, TimeUnit.MILLISECONDS);
        final UAttributes responseAttributes = responseMessage.getAttributes();
        assertEquals(UPayload.getDefaultInstance(), responseMessage.getPayload());
        assertEquals(METHOD_URI, responseAttributes.getSource());
        assertEquals(RESPONSE_URI, responseAttributes.getSink());
        assertEquals(UMessageType.UMESSAGE_TYPE_RESPONSE, responseAttributes.getType());
        assertEquals(requestAttributes.getId(), responseAttributes.getReqid());
        assertEquals(UCode.OK, responseAttributes.getCommstatus());
    }

    @Test
    public void testInvokeMethodSameRequest() {
        doReturn(buildStatus(UCode.OK)).when(mManager).send(any());
        final UAttributesBuilder builder = UAttributesBuilder.request(RESPONSE_URI, METHOD_URI, UPriority.UPRIORITY_CS4, TTL);
        try (MockedStatic<UAttributesBuilder> mockedBuilder = mockStatic(UAttributesBuilder.class)) {
            mockedBuilder.when(() -> UAttributesBuilder.request(RESPONSE_URI, METHOD_URI, UPriority.UPRIORITY_CS4, TTL))
                    .thenReturn(builder);
            mClient.invokeMethod(METHOD_URI, PAYLOAD, OPTIONS);
            assertStatus(UCode.ABORTED, toStatus(assertThrows(ExecutionException.class,
                    () -> mClient.invokeMethod(METHOD_URI, PAYLOAD, OPTIONS).toCompletableFuture().get())));
        }
    }

    @Test
    public void testInvokeMethodSendFailure() {
        doReturn(buildStatus(UCode.UNAVAILABLE)).when(mManager).send(any());
        assertStatus(UCode.UNAVAILABLE, toStatus(assertThrows(ExecutionException.class,
                () -> mClient.invokeMethod(METHOD_URI, PAYLOAD, OPTIONS).toCompletableFuture().get())));
    }
}
