/*
 * Copyright 2018-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.stitch.android.core.internal;

import com.google.android.gms.tasks.Task;
import com.mongodb.stitch.android.core.StitchAppClient;
import com.mongodb.stitch.android.core.auth.StitchAuth;
import com.mongodb.stitch.android.core.auth.StitchAuthListener;
import com.mongodb.stitch.android.core.auth.StitchUser;
import com.mongodb.stitch.android.core.auth.internal.StitchAuthImpl;
import com.mongodb.stitch.android.core.internal.common.MainLooperDispatcher;
import com.mongodb.stitch.android.core.internal.common.TaskDispatcher;
import com.mongodb.stitch.android.core.push.StitchPush;
import com.mongodb.stitch.android.core.push.internal.StitchPushImpl;
import com.mongodb.stitch.android.core.services.StitchServiceClient;
import com.mongodb.stitch.android.core.services.internal.NamedServiceClientFactory;
import com.mongodb.stitch.android.core.services.internal.ServiceClientFactory;
import com.mongodb.stitch.android.core.services.internal.StitchServiceClientImpl;
import com.mongodb.stitch.core.StitchAppClientConfiguration;
import com.mongodb.stitch.core.StitchAppClientInfo;
import com.mongodb.stitch.core.auth.internal.CoreStitchAuth;
import com.mongodb.stitch.core.internal.CoreStitchAppClient;
import com.mongodb.stitch.core.internal.common.AuthMonitor;
import com.mongodb.stitch.core.internal.net.StitchAppRequestClientImpl;
import com.mongodb.stitch.core.internal.net.StitchAppRoutes;
import com.mongodb.stitch.core.services.internal.AuthEvent;
import com.mongodb.stitch.core.services.internal.CoreStitchServiceClient;
import com.mongodb.stitch.core.services.internal.CoreStitchServiceClientImpl;
import com.mongodb.stitch.core.services.internal.RebindEvent;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

import org.bson.codecs.Decoder;
import org.bson.codecs.configuration.CodecRegistry;

public final class StitchAppClientImpl implements StitchAppClient, AuthMonitor, StitchAuthListener {

  private final CoreStitchAppClient coreClient;
  private final TaskDispatcher dispatcher;
  private final StitchAppClientInfo info;
  private final StitchAppRoutes routes;
  private final StitchAuthImpl auth;
  private final StitchPush push;
  /**
   * A list of weak references to any service client created
   * by a user.
   */
  private final List<WeakReference<CoreStitchServiceClient>> serviceClients;

  /**
   * Constructs an app client with the given configuration.
   *
   * @param clientAppId the client app id for the app.
   * @param config      the configuration to use for the app client.
   */
  public StitchAppClientImpl(
      final String clientAppId,
      final StitchAppClientConfiguration config
  ) {
    this.dispatcher = new TaskDispatcher();
    this.info =
        new StitchAppClientInfo(
            clientAppId,
            config.getDataDirectory(),
            config.getLocalAppName(),
            config.getLocalAppVersion(),
            config.getCodecRegistry(),
            config.getNetworkMonitor(),
            this,
            new MainLooperDispatcher());
    this.routes = new StitchAppRoutes(this.info.getClientAppId());
    final StitchAppRequestClientImpl requestClient =
        new StitchAppRequestClientImpl(clientAppId, config.getBaseUrl(), config.getTransport(),
            config.getDefaultRequestTimeout());
    this.auth =
        new StitchAuthImpl(
            requestClient, this.routes.getAuthRoutes(), config.getStorage(), dispatcher, this.info);
    this.coreClient = new CoreStitchAppClient(this.auth, this.routes, config.getCodecRegistry());
    this.push = new StitchPushImpl(this.auth, this.routes.getPushRoutes(), dispatcher);
    this.serviceClients = new ArrayList<>();
    this.auth.addSynchronousAuthListener(this);
  }

  @Override
  public StitchAuth getAuth() {
    return auth;
  }

  @Override
  public StitchPush getPush() {
    return push;
  }

  @Override
  public <T> T getServiceClient(
      final NamedServiceClientFactory<T> factory,
      final String serviceName) {
    final CoreStitchServiceClient serviceClient = new CoreStitchServiceClientImpl(
        auth,
        routes.getServiceRoutes(),
        serviceName,
        info.getCodecRegistry());
    this.bindServiceClient(serviceClient);
    return factory.getClient(serviceClient, info, dispatcher);
  }

  @Override
  public <T> T getServiceClient(final ServiceClientFactory<T> factory) {
    final CoreStitchServiceClient serviceClient = new CoreStitchServiceClientImpl(
        auth,
        routes.getServiceRoutes(),
        "",
        info.getCodecRegistry());

    this.bindServiceClient(serviceClient);
    return factory.getClient(serviceClient, info, dispatcher);
  }

  @Override
  public StitchServiceClient getServiceClient(final String serviceName) {
    final CoreStitchServiceClient serviceClient = new CoreStitchServiceClientImpl(
        auth,
        routes.getServiceRoutes(),
        serviceName,
        info.getCodecRegistry());
    this.bindServiceClient(serviceClient);
    return new StitchServiceClientImpl(serviceClient, dispatcher);
  }

  @Override
  public Task<Void> callFunction(
      final String name,
      final List<?> args) {
    return dispatcher.dispatchTask(
        new Callable<Void>() {
          @Override
          public Void call() {
            coreClient.callFunction(name, args, null);
            return null;
          }
        });
  }

  @Override
  public Task<Void> callFunction(
      final String name,
      final List<?> args,
      final Long requestTimeout
  ) {
    return dispatcher.dispatchTask(
        new Callable<Void>() {
          @Override
          public Void call() {
            coreClient.callFunction(name, args, requestTimeout);
            return null;
          }
        });
  }

  @Override
  public <ResultT> Task<ResultT> callFunction(
      final String name,
      final List<?> args,
      final Class<ResultT> resultClass) {
    return dispatcher.dispatchTask(
        new Callable<ResultT>() {
          @Override
          public ResultT call() {
            return coreClient.callFunction(name, args, null, resultClass);
          }
        });
  }

  @Override
  public <ResultT> Task<ResultT> callFunction(
      final String name,
      final List<?> args,
      final Long requestTimeout,
      final Class<ResultT> resultClass) {
    return dispatcher.dispatchTask(
        new Callable<ResultT>() {
          @Override
          public ResultT call() {
            return coreClient.callFunction(name, args, requestTimeout, resultClass);
          }
        });
  }

  @Override
  public <ResultT> Task<ResultT> callFunction(
      final String name,
      final List<?> args,
      final Class<ResultT> resultClass,
      final CodecRegistry codecRegistry
  ) {
    return dispatcher.dispatchTask(
        new Callable<ResultT>() {
          @Override
          public ResultT call() {
            return coreClient.callFunction(name, args, null, resultClass, codecRegistry);
          }
        });
  }

  @Override
  public <ResultT> Task<ResultT> callFunction(
      final String name,
      final List<?> args,
      final Long requestTimeout,
      final Class<ResultT> resultClass,
      final CodecRegistry codecRegistry
  ) {
    return dispatcher.dispatchTask(
        new Callable<ResultT>() {
          @Override
          public ResultT call() {
            return coreClient.callFunction(
                name,
                args,
                requestTimeout,
                resultClass,
                codecRegistry);
          }
        });
  }

  @Override
  public <ResultT> Task<ResultT> callFunction(
      final String name,
      final List<?> args,
      final Decoder<ResultT> resultDecoder) {
    return dispatcher.dispatchTask(
        new Callable<ResultT>() {
          @Override
          public ResultT call() {
            return coreClient.callFunction(name, args, null, resultDecoder);
          }
        });
  }

  @Override
  public <ResultT> Task<ResultT> callFunction(
      final String name,
      final List<?> args,
      final Long requestTimeout,
      final Decoder<ResultT> resultDecoder) {
    return dispatcher.dispatchTask(
        new Callable<ResultT>() {
          @Override
          public ResultT call() {
            return coreClient.callFunction(name, args, requestTimeout, resultDecoder);
          }
        });
  }

  @Override
  public boolean isLoggedIn() throws InterruptedException {
    return ((CoreStitchAuth)getAuth()).isLoggedInInterruptibly();
  }

  @Override
  public boolean tryIsLoggedIn() {
    try {
      return ((CoreStitchAuth)getAuth()).isLoggedInInterruptibly();
    } catch (InterruptedException e) {
      return false;
    }
  }

  @Nullable
  @Override
  public String getActiveUserId() {
    return getAuth().getUser() != null ? getAuth().getUser().getId() : null;
  }

  /**
   * Bind a given service client to this app client.
   * This allows the app client to dispatch events that require
   * the service to rebind to the application, e.g., a change
   * in authentication.
   *
   * @param coreStitchServiceClient the service client to bind
   */
  private void bindServiceClient(final CoreStitchServiceClient coreStitchServiceClient) {
    this.serviceClients.add(new WeakReference<>(coreStitchServiceClient));
  }

  private void onRebindEvent(final RebindEvent rebindEvent) {
    final Iterator<WeakReference<CoreStitchServiceClient>> iterator =
        this.serviceClients.iterator();
    while (iterator.hasNext()) {
      final WeakReference<CoreStitchServiceClient> weakReference = iterator.next();
      final CoreStitchServiceClient binder = weakReference.get();
      // if the binder has been dealloc'd, remove it from the list
      // else, notify the binder that a rebind event has occurred
      if (binder == null) {
        this.serviceClients.remove(weakReference);
      } else {
        binder.onRebindEvent(rebindEvent);
      }
    }
  }

  @Override
  public void onAuthEvent(final StitchAuth auth) {
  }

  @Override
  public void onUserLoggedIn(final StitchAuth auth,
                             final StitchUser loggedInUser) {
    onRebindEvent(new AuthEvent.UserLoggedIn<>(loggedInUser));
  }

  @Override
  public void onUserLoggedOut(final StitchAuth auth,
                              final StitchUser loggedOutUser) {
    onRebindEvent(new AuthEvent.UserLoggedOut<>(loggedOutUser));
  }

  @Override
  public void onActiveUserChanged(final StitchAuth auth,
                                  final StitchUser currentActiveUser,
                                  final @Nullable StitchUser previousActiveUser) {
    onRebindEvent(new AuthEvent.ActiveUserChanged<>(currentActiveUser, previousActiveUser));
  }

  @Override
  public void onUserRemoved(final StitchAuth auth, final StitchUser removedUser) {
    onRebindEvent(new AuthEvent.UserRemoved<>(removedUser));
  }

  /**
   * Closes the client and shuts down all background operations.
   */
  @Override
  public void close() throws IOException {
    auth.close();
    dispatcher.close();
  }
}
