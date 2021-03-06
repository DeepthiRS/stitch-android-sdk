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

package com.mongodb.stitch.server.services.mongodb.remote.internal;

import com.mongodb.stitch.core.services.mongodb.remote.internal.CoreRemoteMongoCursor;
import com.mongodb.stitch.server.services.mongodb.remote.RemoteMongoCursor;

import java.io.IOException;

class RemoteMongoCursorImpl<ResultT> implements RemoteMongoCursor<ResultT> {
  private final CoreRemoteMongoCursor<ResultT> proxy;

  RemoteMongoCursorImpl(final CoreRemoteMongoCursor<ResultT> cursor) {
    this.proxy = cursor;
  }

  @Override
  public boolean hasNext() {
    return proxy.hasNext();
  }

  @Override
  public ResultT next() {
    return proxy.next();
  }

  @Override
  public void close() throws IOException {
    proxy.close();
  }
}
