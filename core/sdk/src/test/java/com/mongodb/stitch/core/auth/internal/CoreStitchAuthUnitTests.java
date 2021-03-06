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

package com.mongodb.stitch.core.auth.internal;

import static com.mongodb.stitch.core.testutils.ApiTestUtils.getAuthorizationBearer;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getLastDeviceId;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getLastUserId;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getMockedRequestClient;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getTestAccessToken;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getTestLinkResponse;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getTestRefreshToken;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.getTestUserProfile;
import static com.mongodb.stitch.core.testutils.ApiTestUtils.mockOldLoginResponse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mongodb.stitch.core.StitchClientErrorCode;
import com.mongodb.stitch.core.StitchClientException;
import com.mongodb.stitch.core.StitchRequestErrorCode;
import com.mongodb.stitch.core.StitchRequestException;
import com.mongodb.stitch.core.StitchServiceErrorCode;
import com.mongodb.stitch.core.StitchServiceException;
import com.mongodb.stitch.core.auth.internal.models.ApiCoreUserProfile;
import com.mongodb.stitch.core.auth.providers.anonymous.AnonymousAuthProvider;
import com.mongodb.stitch.core.auth.providers.anonymous.AnonymousCredential;
import com.mongodb.stitch.core.auth.providers.userpassword.UserPasswordAuthProvider;
import com.mongodb.stitch.core.auth.providers.userpassword.UserPasswordCredential;
import com.mongodb.stitch.core.internal.common.BsonUtils;
import com.mongodb.stitch.core.internal.common.MemoryStorage;
import com.mongodb.stitch.core.internal.common.StitchObjectMapper;
import com.mongodb.stitch.core.internal.common.Storage;
import com.mongodb.stitch.core.internal.net.ContentTypes;
import com.mongodb.stitch.core.internal.net.Headers;
import com.mongodb.stitch.core.internal.net.Method;
import com.mongodb.stitch.core.internal.net.Response;
import com.mongodb.stitch.core.internal.net.StitchAppRoutes;
import com.mongodb.stitch.core.internal.net.StitchAuthDocRequest;
import com.mongodb.stitch.core.internal.net.StitchDocRequest;
import com.mongodb.stitch.core.internal.net.StitchRequest;
import com.mongodb.stitch.core.internal.net.StitchRequestClient;
import com.mongodb.stitch.core.testutils.CustomType;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nullable;

import org.bson.Document;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.IntegerCodec;
import org.bson.codecs.StringCodec;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.types.ObjectId;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;


public class CoreStitchAuthUnitTests {

  @Test
  public void testLoginWithCredentialBlocking() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = spy(new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage()));

    final CoreStitchUserImpl user =
        auth.loginWithCredentialInternal(new AnonymousCredential());

    final ApiCoreUserProfile profile = getTestUserProfile();
    assertEquals(getLastUserId(), user.getId());
    assertEquals(AnonymousAuthProvider.DEFAULT_NAME, user.getLoggedInProviderName());
    assertEquals(AnonymousAuthProvider.TYPE, user.getLoggedInProviderType());
    assertEquals(profile.getUserType(), user.getUserType());
    assertEquals(profile.getIdentities().get(0).getId(), user.getIdentities().get(0).getId());
    assertEquals(auth.getUser(), user);
    assertTrue(auth.isLoggedIn());

    final ArgumentCaptor<StitchRequest> reqArgs = ArgumentCaptor.forClass(StitchRequest.class);
    verify(requestClient, times(2)).doRequest(reqArgs.capture());

    final StitchDocRequest.Builder expectedRequest = new StitchDocRequest.Builder();
    expectedRequest.withMethod(Method.POST)
        .withPath(routes.getAuthProviderLoginRoute(AnonymousAuthProvider.DEFAULT_NAME));
    expectedRequest.withDocument(new Document("options", new Document("device", new Document())));
    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(0));

    final StitchRequest.Builder expectedRequest2 = new StitchRequest.Builder();
    final Map<String, String> headers = new HashMap<>();
    headers.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestAccessToken()));
    expectedRequest2.withMethod(Method.GET)
        .withPath(routes.getProfileRoute())
        .withHeaders(headers);
    assertEquals(expectedRequest2.build(), reqArgs.getAllValues().get(1));

    verify(auth, times(1)).onUserLoggedIn(eq(user));
  }

  @Test
  public void testLinkUserWithCredentialBlocking() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = spy(new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage()));

    final CoreStitchUserImpl user =
        auth.loginWithCredentialInternal(new AnonymousCredential());
    verify(requestClient, times(2)).doRequest(any(StitchRequest.class));

    final CoreStitchUserImpl linkedUser =
        auth.linkUserWithCredentialInternal(
            user,
            new UserPasswordCredential("foo@foo.com", "bar"));

    assertEquals(user.getId(), linkedUser.getId());

    final ArgumentCaptor<StitchRequest> reqArgs = ArgumentCaptor.forClass(StitchRequest.class);
    verify(requestClient, times(4)).doRequest(reqArgs.capture());

    final StitchRequest.Builder expectedRequest = new StitchRequest.Builder();
    expectedRequest.withMethod(Method.POST)
        .withBody(String.format(
            "{\"username\" : \"foo@foo.com\",\"password\" : \"bar\","
            + "\"options\" : {\"device\" : {\"deviceId\" : \"%s\"}}}",
            getLastDeviceId()).getBytes(StandardCharsets.UTF_8))
        .withPath(routes.getAuthProviderLinkRoute(UserPasswordAuthProvider.DEFAULT_NAME));
    final Map<String, String> headers = new HashMap<>();
    headers.put(Headers.CONTENT_TYPE, ContentTypes.APPLICATION_JSON);
    headers.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestAccessToken()));
    expectedRequest.withHeaders(headers);

    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(2));

    final StitchRequest.Builder expectedRequest2 = new StitchRequest.Builder();
    final Map<String, String> headers2 = new HashMap<>();
    headers2.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestAccessToken()));
    expectedRequest2.withMethod(Method.GET)
        .withPath(routes.getProfileRoute())
        .withHeaders(headers2);
    assertEquals(expectedRequest2.build(), reqArgs.getAllValues().get(3));

    verify(auth, times(1)).onUserLinked(eq(linkedUser));
  }

  @Test
  public void testIsLoggedIn() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());

    assertFalse(auth.isLoggedIn());

    auth.loginWithCredentialInternal(new AnonymousCredential());

    assertTrue(auth.isLoggedIn());
  }

  @Test
  public void testLogoutBlocking() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = spy(new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage()));

    assertFalse(auth.isLoggedIn());

    final CoreStitchUserImpl user1 =
        auth.loginWithCredentialInternal(new AnonymousCredential());

    verify(auth, times(1)).onUserLoggedIn(eq(user1));

    final CoreStitchUserImpl user2 =
        auth.loginWithCredentialInternal(new UserPasswordCredential("hi", "there"));

    verify(auth, times(1)).onUserLoggedIn(eq(user2));
    verify(auth, times(1)).onActiveUserChanged(eq(user2), eq(user1));

    final CoreStitchUserImpl user3 =
        auth.loginWithCredentialInternal(new UserPasswordCredential("bye", "there"));

    verify(auth, times(1)).onUserLoggedIn(eq(user3));
    verify(auth, times(1)).onActiveUserChanged(eq(user3), eq(user2));

    assertEquals(auth.listUsers().get(2), user3);
    assertTrue(auth.isLoggedIn());

    auth.logoutInternal();

    verify(auth, times(1)).onUserLoggedOut(eq(user3));

    // assert that though one user is logged out, three users are still listed, and their profiles
    // are all non-null
    assertEquals(auth.listUsers().size(), 3);
    assertTrue(auth.listUsers().stream().allMatch(new Predicate<CoreStitchUserImpl>() {
      @Override
      public boolean test(final CoreStitchUserImpl coreStitchUser) {
        return coreStitchUser.getProfile() != null;
      }
    }));

    final ArgumentCaptor<StitchRequest> reqArgs = ArgumentCaptor.forClass(StitchRequest.class);
    verify(requestClient, times(7)).doRequest(reqArgs.capture());

    final StitchRequest.Builder expectedRequest = new StitchRequest.Builder();
    expectedRequest.withMethod(Method.DELETE)
        .withPath(routes.getSessionRoute());
    final Map<String, String> headers = new HashMap<>();
    headers.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestRefreshToken()));
    expectedRequest.withHeaders(headers);
    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(6));

    assertFalse(auth.isLoggedIn());
    auth.switchToUserWithId(user2.getId());
    verify(auth, times(1)).onActiveUserChanged(eq(user2), eq(null));
    auth.logoutInternal();

    verify(requestClient, times(8)).doRequest(reqArgs.capture());
    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(13));

    assertEquals(auth.listUsers().size(), 3);

    // log out the last user without switching to them
    auth.logoutUserWithIdInternal(user1.getId());

    // assert that this leaves only two users since logging out an anonymous
    // user deletes that user, and assert they are all logged out
    assertEquals(auth.listUsers().size(), 2);
    assertTrue(auth.listUsers().stream().allMatch(new Predicate<CoreStitchUserImpl>() {
          @Override
          public boolean test(final CoreStitchUserImpl coreStitchUser) {
            return !coreStitchUser.isLoggedIn();
          }
        }));

    assertFalse(auth.isLoggedIn());

    // assert that we still have a device ID
    assertTrue(auth.hasDeviceId());

    // assert that logging back into a non-anon user after logging out works
    mockOldLoginResponse(requestClient, user2.getId(), user2.getDeviceId());

    assertEquals(
        user2, auth.loginWithCredentialInternal(new UserPasswordCredential("hi", "there")));

    assertEquals(auth.listUsers().size(), 2);

    // assert that the list ordering has been preserved (except for the now-missing anonymous user)
    assertEquals(auth.listUsers().get(0).getId(), user2.getId());
    assertEquals(auth.listUsers().get(1).getId(), user3.getId());

    assertTrue(auth.isLoggedIn());
  }

  @Test
  public void testRemoveBlocking() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = spy(new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage()));

    assertFalse(auth.isLoggedIn());

    final CoreStitchUserImpl user1 =
        auth.loginWithCredentialInternal(new AnonymousCredential());
    final CoreStitchUserImpl user2 =
        auth.loginWithCredentialInternal(new UserPasswordCredential("hi", "there"));
    final CoreStitchUserImpl user3 =
        auth.loginWithCredentialInternal(new UserPasswordCredential("bye", "there"));

    assertEquals(auth.listUsers().get(2), user3);
    assertTrue(auth.isLoggedIn());

    auth.removeUserInternal();
    verify(auth, times(1)).onUserRemoved(user3);

    // assert that though one user is logged out, two users are still listed
    assertEquals(auth.listUsers().size(), 2);
    assertEquals(auth.listUsers().get(1), user2);

    final ArgumentCaptor<StitchRequest> reqArgs = ArgumentCaptor.forClass(StitchRequest.class);
    verify(requestClient, times(7)).doRequest(reqArgs.capture());

    final StitchRequest.Builder expectedRequest = new StitchRequest.Builder();
    expectedRequest.withMethod(Method.DELETE)
        .withPath(routes.getSessionRoute());
    final Map<String, String> headers = new HashMap<>();
    headers.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestRefreshToken()));
    expectedRequest.withHeaders(headers);
    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(6));


    // assert that switching to a user, and removing self works
    assertFalse(auth.isLoggedIn());
    auth.switchToUserWithId(user2.getId());
    verify(auth, times(1)).onActiveUserChanged(eq(user2), eq(null));
    assertTrue(auth.isLoggedIn());

    auth.removeUserInternal();

    verify(requestClient, times(8)).doRequest(reqArgs.capture());
    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(13));


    // assert that there is one user left
    assertEquals(auth.listUsers().size(), 1);
    assertEquals(auth.listUsers().get(0), user1);

    // assert that we can remove the user without switching to it
    auth.removeUserWithIdInternal(user1.getId());
    verify(auth, times(1)).onUserRemoved(user1);

    assertEquals(auth.listUsers().size(), 0);

    assertFalse(auth.isLoggedIn());

    // assert that we can log back into the user that we removed
    mockOldLoginResponse(requestClient, user2.getId(), user2.getDeviceId());

    assertEquals(
        user2, auth.loginWithCredentialInternal(new UserPasswordCredential("hi", "there")));

    verify(auth, times(2)).onUserLoggedIn(user2);

    assertEquals(auth.listUsers().size(), 1);
    assertEquals(auth.listUsers().get(0), user2);

    assertTrue(auth.isLoggedIn());
  }

  @Test
  public void testHasDeviceId() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());

    assertFalse(auth.hasDeviceId());

    auth.loginWithCredentialInternal(new AnonymousCredential());

    assertTrue(auth.hasDeviceId());
  }

  @Test
  public void testHandleAuthFailure() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());

    final CoreStitchUser user = auth.loginWithCredentialInternal(new AnonymousCredential());

    final Map<String, Object> claims = new HashMap<>();
    claims.put("typ", "access");
    claims.put("test_refreshed", true);
    final String refreshedJwt = Jwts.builder()
        .setClaims(claims)
        .setIssuedAt(Date.from(Instant.now().minus(Duration.ofHours(1))))
        .setSubject("uniqueUserID")
        .setExpiration(new Date(((Calendar.getInstance().getTimeInMillis() + (5 * 60 * 1000)))))
        .signWith(
            SignatureAlgorithm.HS256,
            "abcdefghijklmnopqrstuvwxyz1234567890".getBytes(StandardCharsets.UTF_8))
        .compact();
    doReturn(new Response(new Document("access_token", refreshedJwt).toJson()))
        .when(requestClient)
        .doRequest(argThat(req -> req.getMethod() == Method.POST
            && req.getPath().endsWith("/session")));

    doThrow(new StitchServiceException(StitchServiceErrorCode.INVALID_SESSION))
        .doReturn(
            new Response(getTestLinkResponse().toString()))
        .when(requestClient)
        .doRequest(argThat(req -> req.getPath().endsWith("/login?link=true")));

    final CoreStitchUser linkedUser =
        auth.linkUserWithCredentialInternal(
            user,
            new UserPasswordCredential("foo@foo.com", "bar"));

    final ArgumentCaptor<StitchRequest> reqArgs = ArgumentCaptor.forClass(StitchRequest.class);
    verify(requestClient, times(6)).doRequest(reqArgs.capture());

    final StitchRequest.Builder expectedRequest = new StitchRequest.Builder();
    expectedRequest.withMethod(Method.POST)
        .withPath(routes.getSessionRoute());
    final Map<String, String> headers = new HashMap<>();
    headers.put(Headers.AUTHORIZATION, getAuthorizationBearer(getTestRefreshToken()));
    expectedRequest.withHeaders(headers);
    assertEquals(expectedRequest.build(), reqArgs.getAllValues().get(3));

    final StitchRequest.Builder expectedRequest2 = new StitchRequest.Builder();
    expectedRequest2.withMethod(Method.POST)
        .withBody(String.format(
            "{\"username\" : \"foo@foo.com\",\"password\" : \"bar\","
            + "\"options\" : {\"device\" : {\"deviceId\" : \"%s\"}}}",
            getLastDeviceId()).getBytes(StandardCharsets.UTF_8))
        .withPath(routes.getAuthProviderLinkRoute(UserPasswordAuthProvider.DEFAULT_NAME));
    final Map<String, String> headers2 = new HashMap<>();
    headers2.put(Headers.CONTENT_TYPE, ContentTypes.APPLICATION_JSON);
    headers2.put(Headers.AUTHORIZATION, getAuthorizationBearer(refreshedJwt));
    expectedRequest2.withHeaders(headers2);
    assertEquals(expectedRequest2.build(), reqArgs.getAllValues().get(4));

    assertTrue(auth.isLoggedIn());

    // This should log the user out
    doThrow(new StitchServiceException(StitchServiceErrorCode.INVALID_SESSION))
        .when(requestClient)
        .doRequest(argThat(req -> req.getMethod() == Method.POST
            && req.getPath().endsWith("/session")));
    doThrow(new StitchServiceException(StitchServiceErrorCode.INVALID_SESSION))
        .when(requestClient)
        .doRequest(argThat(req -> req.getPath().endsWith("/login?link=true")));

    try {
      auth.linkUserWithCredentialInternal(
          linkedUser,
          new UserPasswordCredential("foo@foo.com", "bar"));
      fail();
    } catch (final StitchServiceException ex) {
      assertEquals(ex.getErrorCode(), StitchServiceErrorCode.INVALID_SESSION);
    }

    // the failure due to an invalid session will log the user out entirely
    assertFalse(auth.isLoggedIn());
  }

  @Test
  public void testDoAuthenticatedRequestWithDefaultCodecRegistry() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());
    auth.loginWithCredentialInternal(new AnonymousCredential());

    final StitchAuthDocRequest.Builder reqBuilder = new StitchAuthDocRequest.Builder();
    reqBuilder.withPath("giveMeData");
    reqBuilder.withDocument(new Document());
    reqBuilder.withMethod(Method.POST);

    final String rawInt = "{\"$numberInt\": \"42\"}";
    // Check that primitive return types can be decoded.
    doReturn(new Response(rawInt)).when(requestClient).doRequest(any(StitchRequest.class));
    assertEquals(42, (int) auth.doAuthenticatedRequest(
        reqBuilder.build(),
        Integer.class, BsonUtils.DEFAULT_CODEC_REGISTRY));
    doReturn(new Response(rawInt)).when(requestClient).doRequest(any(StitchRequest.class));
    assertEquals(42, (int) auth.doAuthenticatedRequest(
        reqBuilder.build(),
        new IntegerCodec()));

    // Check that the proper exceptions are thrown when decoding into the incorrect type.
    doReturn(new Response(rawInt)).when(requestClient).doRequest(any(StitchRequest.class));
    try {
      auth.doAuthenticatedRequest(
          reqBuilder.build(),
          String.class,
          BsonUtils.DEFAULT_CODEC_REGISTRY);
      fail();
    } catch (final StitchRequestException ignored) {
      // do nothing
    }

    doReturn(new Response(rawInt)).when(requestClient).doRequest(any(StitchRequest.class));
    try {
      auth.doAuthenticatedRequest(reqBuilder.build(), new StringCodec());
      fail();
    } catch (final StitchRequestException ignored) {
      // do nothing
    }

    // Check that BSON documents returned as extended JSON can be decoded.
    final ObjectId expectedObjectId = new ObjectId();
    final String docRaw =
        String.format(
            "{\"_id\": {\"$oid\": \"%s\"}, \"intValue\": {\"$numberInt\": \"42\"}}",
            expectedObjectId.toHexString());
    doReturn(new Response(docRaw)).when(requestClient).doRequest(any(StitchRequest.class));

    Document doc = auth.doAuthenticatedRequest(
        reqBuilder.build(),
        Document.class,
        BsonUtils.DEFAULT_CODEC_REGISTRY);
    assertEquals(expectedObjectId, doc.getObjectId("_id"));
    assertEquals(42, (int) doc.getInteger("intValue"));

    doReturn(new Response(docRaw)).when(requestClient).doRequest(any(StitchRequest.class));
    doc = auth.doAuthenticatedRequest(reqBuilder.build(), new DocumentCodec());
    assertEquals(expectedObjectId, doc.getObjectId("_id"));
    assertEquals(42, (int) doc.getInteger("intValue"));

    // Check that BSON documents returned as extended JSON can be decoded as a custom type if
    // the codec is specifically provided.
    doReturn(new Response(docRaw)).when(requestClient).doRequest(any(StitchRequest.class));
    final CustomType ct =
        auth.doAuthenticatedRequest(reqBuilder.build(), new CustomType.Codec());
    assertEquals(expectedObjectId, ct.getId());
    assertEquals(42, ct.getIntValue());

    // Check that the correct exception is thrown if attempting to decode as a particular class
    // type if the auth was never configured to contain the provided class type
    // codec.
    doReturn(new Response(docRaw)).when(requestClient).doRequest(any(StitchRequest.class));
    try {
      auth.doAuthenticatedRequest(
          reqBuilder.build(),
          CustomType.class,
          BsonUtils.DEFAULT_CODEC_REGISTRY);
      fail();
    } catch (final StitchRequestException ignored) {
      // do nothing
    }

    // Check that BSON arrays can be decoded
    final List<Object> arrFromServer =
        Arrays.asList(21, "the meaning of life, the universe, and everything", 84, 168);
    final String arrFromServerRaw;
    try {
      arrFromServerRaw = StitchObjectMapper.getInstance().writeValueAsString(arrFromServer);
    } catch (final JsonProcessingException e) {
      fail(e.getMessage());
      return;
    }
    doReturn(new Response(arrFromServerRaw)).when(requestClient)
        .doRequest(any(StitchRequest.class));

    @SuppressWarnings("unchecked")
    final List<Object> list = auth.doAuthenticatedRequest(
        reqBuilder.build(),
        List.class,
        BsonUtils.DEFAULT_CODEC_REGISTRY);
    assertEquals(arrFromServer, list);
  }

  @Test
  public void testDoAuthenticatedRequestWithCustomCodecRegistry() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth =
        new StitchAuth(
            requestClient,
            routes,
            new MemoryStorage());
    final CodecRegistry registry = CodecRegistries.fromRegistries(
        BsonUtils.DEFAULT_CODEC_REGISTRY,
        CodecRegistries.fromCodecs(new CustomType.Codec()));
    auth.loginWithCredentialInternal(new AnonymousCredential());

    final StitchAuthDocRequest.Builder reqBuilder = new StitchAuthDocRequest.Builder();
    reqBuilder.withPath("giveMeData");
    reqBuilder.withDocument(new Document());
    reqBuilder.withMethod(Method.POST);

    final String rawInt = "{\"$numberInt\": \"42\"}";
    // Check that primitive return types can be decoded.
    doReturn(new Response(rawInt)).when(requestClient).doRequest(any(StitchRequest.class));
    assertEquals(42, (int) auth.doAuthenticatedRequest(
        reqBuilder.build(),
        Integer.class,
        registry));
    doReturn(new Response(rawInt)).when(requestClient).doRequest(any(StitchRequest.class));
    assertEquals(42, (int) auth.doAuthenticatedRequest(
        reqBuilder.build(),
        new IntegerCodec()));

    final ObjectId expectedObjectId = new ObjectId();
    final String docRaw =
        String.format(
            "{\"_id\": {\"$oid\": \"%s\"}, \"intValue\": {\"$numberInt\": \"42\"}}",
            expectedObjectId.toHexString());

    // Check that BSON documents returned as extended JSON can be decoded into BSON
    // documents.
    doReturn(new Response(docRaw)).when(requestClient).doRequest(any(StitchRequest.class));
    Document doc = auth.doAuthenticatedRequest(reqBuilder.build(), Document.class, registry);
    assertEquals(expectedObjectId, doc.getObjectId("_id"));
    assertEquals(42, (int) doc.getInteger("intValue"));

    doReturn(new Response(docRaw)).when(requestClient).doRequest(any(StitchRequest.class));
    doc = auth.doAuthenticatedRequest(reqBuilder.build(), new DocumentCodec());
    assertEquals(expectedObjectId, doc.getObjectId("_id"));
    assertEquals(42, (int) doc.getInteger("intValue"));

    // Check that a custom type can be decoded without providing a codec, as long as that codec
    // is registered in the CoreStitchAuth's configuration.
    doReturn(new Response(docRaw)).when(requestClient).doRequest(any(StitchRequest.class));
    final CustomType ct = auth.doAuthenticatedRequest(
        reqBuilder.build(),
        CustomType.class,
        registry);
    assertEquals(expectedObjectId, ct.getId());
    assertEquals(42, ct.getIntValue());
  }

  @Test
  public void testProfileRequestFailureEdgeCases() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth =
            new StitchAuth(
                    requestClient,
                    routes,
                    new MemoryStorage());

    // Scenario 1: User is logged out -> attempts login -> initial login succeeds
    //                                -> profile request fails -> user is logged out

    doThrow(new StitchRequestException(
            new Exception("profile request failed"), StitchRequestErrorCode.TRANSPORT_ERROR))
            .when(requestClient)
            .doRequest(ArgumentMatchers.argThat(req -> req.getPath().endsWith("/profile")));

    try {
      auth.loginWithCredentialInternal(new AnonymousCredential());
      fail("expected login to fail because of profile request");
    } catch (Exception e) {
      // do nothing
    }

    assertFalse(auth.isLoggedIn());
    assertNull(auth.getUser());

    // Scenario 2: User is logged in -> attempts login into other account -> initial login succeeds
    //                               -> profile request fails -> original user is still logged in
    doReturn(new Response(getTestUserProfile().toString()))
            .doThrow(
                    new StitchRequestException(
                    new Exception("profile request failed"),
                    StitchRequestErrorCode.TRANSPORT_ERROR)
            )
            .when(requestClient)
            .doRequest(ArgumentMatchers.argThat(req -> req.getPath().endsWith("/profile")));

    final CoreStitchUser user1 = auth.loginWithCredentialInternal(new AnonymousCredential());
    assertNotNull(user1);

    doThrow(new StitchRequestException(
            new Exception("profile request failed"), StitchRequestErrorCode.TRANSPORT_ERROR))
            .when(requestClient)
            .doRequest(ArgumentMatchers.argThat(req -> req.getPath().endsWith("/profile")));

    try {
      auth.loginWithCredentialInternal(
              new UserPasswordCredential("foo", "bar")
      );
    } catch (Exception e) {
      // do nothing
    }

    assertTrue(auth.isLoggedIn());
    assertEquals(auth.getUser().getId(), user1.getId());

    // Scenario 3: User is logged in -> attempt to link to other identity
    //                               -> initial link request succeeds
    //                               -> profile request fails -> error thrown
    //                               -> original user is still logged in
    doReturn(new Response(getTestUserProfile().toString()))
            .doThrow(
                    new StitchRequestException(
                    new Exception("profile request failed"),
                    StitchRequestErrorCode.TRANSPORT_ERROR
            ))
            .when(requestClient)
            .doRequest(ArgumentMatchers.argThat(req -> req.getPath().endsWith("/profile")));

    final CoreStitchUser userToBeLinked =
            auth.loginWithCredentialInternal(new AnonymousCredential());

    doThrow(new StitchRequestException(
            new Exception("profile request failed"), StitchRequestErrorCode.TRANSPORT_ERROR))
            .when(requestClient)
            .doRequest(ArgumentMatchers.argThat(req -> req.getPath().endsWith("/profile")));

    try {
      auth.linkUserWithCredentialInternal(
              userToBeLinked, new UserPasswordCredential("hello ", "friend")
      );
    } catch (Exception e) {
      // do nothing
    }

    assertTrue(auth.isLoggedIn());
    assertEquals(userToBeLinked.getId(), auth.getUser().getId());
  }

  @Test
  public void testSwitchUser() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = spy(new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage()));

    try {
      auth.switchToUserWithId("not_a_user_id");
      fail("should have thrown error due to missing key");
    } catch (StitchClientException e) {
      verify(auth, times(0)).onActiveUserChanged(any(), any());
      assertEquals(StitchClientErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    final CoreStitchUserImpl user =
        auth.loginWithCredentialInternal(new UserPasswordCredential("greetings ", "friend"));

    // can switch to self
    assertEquals(user, auth.switchToUserWithId(user.getId()));
    assertEquals(user, auth.getUser());
    verify(auth, times(1)).onActiveUserChanged(eq(user), eq(user));

    final CoreStitchUserImpl user2 =
        auth.loginWithCredentialInternal(new UserPasswordCredential("hello ", "friend"));

    assertEquals(user2, auth.getUser());
    assertNotEquals(user, auth.getUser());
    verify(auth, times(1)).onActiveUserChanged(eq(user2), eq(user));

    // can switch back to old user
    assertEquals(user, auth.switchToUserWithId(user.getId()));
    assertEquals(user, auth.getUser());
    verify(auth, times(1)).onActiveUserChanged(eq(user), eq(user2));

    // switch back to second user after logging out
    auth.logoutInternal();
    assertEquals(2, auth.listUsers().size());
    assertEquals(user2, auth.switchToUserWithId(user2.getId()));
    verify(auth, times(1)).onUserLoggedOut(eq(user));
    verify(auth, times(1)).onActiveUserChanged(eq(user2), eq(null));

    // assert that we can't switch to a logged out user
    try {
      auth.switchToUserWithId(user.getId());
      fail("should have thrown error due to logged out user");
    } catch (StitchClientException e) {
      assertEquals(StitchClientErrorCode.USER_NOT_LOGGED_IN, e.getErrorCode());
    }
  }

  @Test
  public void testListUsers() {
    final StitchRequestClient requestClient = getMockedRequestClient();
    final StitchAuthRoutes routes = new StitchAppRoutes("my_app-12345").getAuthRoutes();
    final StitchAuth auth = new StitchAuth(
        requestClient,
        routes,
        new MemoryStorage());

    final CoreStitchUser user =
        auth.loginWithCredentialInternal(new AnonymousCredential());

    assertTrue(auth.listUsers().stream().allMatch(Predicate.isEqual(user)));

    final CoreStitchUser user2 =
        auth.loginWithCredentialInternal(new UserPasswordCredential("hello ", "friend"));

    assertEquals(auth.listUsers(), Arrays.asList(user, user2));
  }

  protected static class StitchAuth extends CoreStitchAuth<CoreStitchUserImpl> {
    StitchAuth(
        final StitchRequestClient requestClient,
        final StitchAuthRoutes authRoutes,
        final Storage storage) {
      super(requestClient, authRoutes, storage, false);
    }

    @Override
    protected StitchUserFactory<CoreStitchUserImpl> getUserFactory() {
      return (String id,
              String deviceId,
              String loggedInProviderType,
              String loggedInProviderName,
              StitchUserProfileImpl userProfile,
              boolean isLoggedIn) ->
          new CoreStitchUserImpl(
              id, deviceId, loggedInProviderType, loggedInProviderName, userProfile, isLoggedIn) {
          };
    }

    @Override
    protected void onAuthEvent() {
    }

    @Override
    protected void onUserLoggedOut(final CoreStitchUserImpl loggedOutUser) {
    }

    @Override
    protected void onUserRemoved(final CoreStitchUserImpl removedUser) {
    }

    @Override
    protected void onUserLoggedIn(final CoreStitchUserImpl loggedInUser) {
    }

    @Override
    protected void onUserAdded(final CoreStitchUserImpl createdUser) {
    }

    @Override
    protected void onActiveUserChanged(final CoreStitchUserImpl currentActiveUser,
                                       @Nullable final CoreStitchUserImpl previousActiveUser) {
    }

    @Override
    protected void onListenerInitialized() {
    }

    @Override
    protected void onUserLinked(final CoreStitchUserImpl linkedUser) {
    }
  }
}
