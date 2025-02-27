package uk.gov.di.authentication.api;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.GrantType;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost;
import com.nimbusds.oauth2.sdk.auth.JWTAuthenticationClaimsSet;
import com.nimbusds.oauth2.sdk.auth.PrivateKeyJWT;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.Audience;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.oauth2.sdk.util.JSONArrayUtils;
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils;
import com.nimbusds.oauth2.sdk.util.URLUtils;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCClaimsRequest;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.SubjectType;
import com.nimbusds.openid.connect.sdk.claims.ClaimsSetRequest;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.di.authentication.oidc.lambda.TokenHandler;
import uk.gov.di.authentication.shared.entity.ClientConsent;
import uk.gov.di.authentication.shared.entity.ClientType;
import uk.gov.di.authentication.shared.entity.RefreshTokenStore;
import uk.gov.di.authentication.shared.entity.ServiceType;
import uk.gov.di.authentication.shared.entity.ValidScopes;
import uk.gov.di.authentication.shared.entity.VectorOfTrust;
import uk.gov.di.authentication.shared.helpers.IdGenerator;
import uk.gov.di.authentication.shared.helpers.NowHelper;
import uk.gov.di.authentication.shared.serialization.Json;
import uk.gov.di.authentication.sharedtest.basetest.ApiGatewayHandlerIntegrationTest;
import uk.gov.di.authentication.sharedtest.helper.AuditAssertionsHelper;
import uk.gov.di.authentication.sharedtest.helper.JsonArrayHelper;
import uk.gov.di.authentication.sharedtest.helper.KeyPairHelper;

import java.net.URI;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.di.authentication.shared.entity.IdentityClaims.VOT;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasBody;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

public class TokenIntegrationTest extends ApiGatewayHandlerIntegrationTest {

    private static final String TOKEN_ENDPOINT = "/token";
    private static final String TEST_EMAIL = "joe.bloggs@digital.cabinet-office.gov.uk";
    private static final String CLIENT_ID = "test-id";
    private static final String DIFFERENT_CLIENT_ID = "different-test-id";
    private static final String REFRESH_TOKEN_PREFIX = "REFRESH_TOKEN:";
    private static final String REDIRECT_URI = "http://localhost/redirect";

    @BeforeEach
    void setup() {
        handler = new TokenHandler(TXMA_ENABLED_CONFIGURATION_SERVICE);
        txmaAuditQueue.clear();
    }

    private static Stream<Arguments> validVectorValues() {
        return Stream.of(
                Arguments.of(Optional.of("Cl.Cm"), "Cl.Cm", Optional.of(CLIENT_ID)),
                Arguments.of(Optional.of("Cl"), "Cl", Optional.of(CLIENT_ID)),
                Arguments.of(Optional.of("P2.Cl.Cm"), "Cl.Cm", Optional.of(CLIENT_ID)),
                Arguments.of(Optional.empty(), "Cl.Cm", Optional.of(CLIENT_ID)),
                Arguments.of(Optional.of("Cl.Cm"), "Cl.Cm", Optional.empty()),
                Arguments.of(Optional.of("Cl"), "Cl", Optional.empty()),
                Arguments.of(Optional.of("P2.Cl.Cm"), "Cl.Cm", Optional.empty()),
                Arguments.of(Optional.empty(), "Cl.Cm", Optional.empty()));
    }

    @ParameterizedTest
    @MethodSource("validVectorValues")
    void shouldCallTokenResourceAndReturnAccessAndRefreshTokenWhenAuthenticatingWithPrivateKeyJwt(
            Optional<String> vtr, String expectedVotClaim, Optional<String> clientId)
            throws Exception {
        KeyPair keyPair = KeyPairHelper.GENERATE_RSA_KEY_PAIR();
        Scope scope =
                new Scope(
                        OIDCScopeValue.OPENID.getValue(), OIDCScopeValue.OFFLINE_ACCESS.getValue());
        registerUser(scope, new Subject());
        registerClientWithPrivateKeyJwtAuthentication(
                keyPair.getPublic(), scope, SubjectType.PAIRWISE);
        var baseTokenRequest = constructBaseTokenRequest(scope, vtr, Optional.empty(), clientId);
        var response = makeTokenRequestWithPrivateKeyJWT(baseTokenRequest, keyPair.getPrivate());

        assertThat(response, hasStatus(200));
        JSONObject jsonResponse = JSONObjectUtils.parse(response.getBody());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getRefreshToken());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getBearerAccessToken());

        assertThat(
                OIDCTokenResponse.parse(jsonResponse)
                        .getOIDCTokens()
                        .getIDToken()
                        .getJWTClaimsSet()
                        .getClaim(VOT.getValue()),
                equalTo(expectedVotClaim));

        AuditAssertionsHelper.assertNoTxmaAuditEventsReceived(txmaAuditQueue);
    }

    @Test
    void
            shouldCallTokenResourceAndReturnAccessAndRefreshTokenWhenAuthenticatingWithClientSecretBasic()
                    throws Exception {
        var clientSecret = new Secret();
        var scope =
                new Scope(
                        OIDCScopeValue.OPENID.getValue(), OIDCScopeValue.OFFLINE_ACCESS.getValue());
        registerUser(scope, new Subject());
        registerClientSecretClient(
                clientSecret.getValue(), ClientAuthenticationMethod.CLIENT_SECRET_BASIC, scope);
        var baseTokenRequest =
                constructBaseTokenRequest(
                        scope, Optional.of("Cl.Cm"), Optional.empty(), Optional.of(CLIENT_ID));
        var response = makeTokenRequestWithClientSecretBasic(baseTokenRequest, clientSecret);

        assertThat(response, hasStatus(200));
        var jsonResponse = JSONObjectUtils.parse(response.getBody());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getRefreshToken());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getBearerAccessToken());

        assertThat(
                OIDCTokenResponse.parse(jsonResponse)
                        .getOIDCTokens()
                        .getIDToken()
                        .getJWTClaimsSet()
                        .getClaim(VOT.getValue()),
                equalTo("Cl.Cm"));

        AuditAssertionsHelper.assertNoTxmaAuditEventsReceived(txmaAuditQueue);
    }

    @Test
    void
            shouldCallTokenResourceAndReturnAccessAndRefreshTokenWhenAuthenticatingWithClientSecretPost()
                    throws Exception {
        var clientSecret = new Secret();
        var scope =
                new Scope(
                        OIDCScopeValue.OPENID.getValue(), OIDCScopeValue.OFFLINE_ACCESS.getValue());
        registerUser(scope, new Subject());
        registerClientSecretClient(
                clientSecret.getValue(), ClientAuthenticationMethod.CLIENT_SECRET_POST, scope);
        var baseTokenRequest =
                constructBaseTokenRequest(
                        scope, Optional.of("Cl.Cm"), Optional.empty(), Optional.of(CLIENT_ID));
        var response = makeTokenRequestWithClientSecretPost(baseTokenRequest, clientSecret);

        assertThat(response, hasStatus(200));
        var jsonResponse = JSONObjectUtils.parse(response.getBody());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getRefreshToken());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getBearerAccessToken());

        assertThat(
                OIDCTokenResponse.parse(jsonResponse)
                        .getOIDCTokens()
                        .getIDToken()
                        .getJWTClaimsSet()
                        .getClaim(VOT.getValue()),
                equalTo("Cl.Cm"));

        AuditAssertionsHelper.assertNoTxmaAuditEventsReceived(txmaAuditQueue);
    }

    @Test
    void shouldCallTokenResourceAndReturn400WhenClientIdParameterDoesNotMatch() throws Exception {
        KeyPair keyPair = KeyPairHelper.GENERATE_RSA_KEY_PAIR();
        Scope scope =
                new Scope(
                        OIDCScopeValue.OPENID.getValue(), OIDCScopeValue.OFFLINE_ACCESS.getValue());
        registerUser(scope, new Subject());
        registerClientWithPrivateKeyJwtAuthentication(
                keyPair.getPublic(), scope, SubjectType.PAIRWISE);
        var baseTokenRequest =
                constructBaseTokenRequest(
                        scope,
                        Optional.of("Cl.Cm"),
                        Optional.empty(),
                        Optional.of(DIFFERENT_CLIENT_ID));

        var response = makeTokenRequestWithPrivateKeyJWT(baseTokenRequest, keyPair.getPrivate());

        assertThat(response, hasStatus(400));
        assertThat(
                response,
                hasBody(
                        new ErrorObject(OAuth2Error.INVALID_REQUEST_CODE, "Invalid private_key_jwt")
                                .toJSONObject()
                                .toJSONString()));
    }

    @Test
    void shouldReturnIdTokenWithPublicSubjectId() throws Exception {
        KeyPair keyPair = KeyPairHelper.GENERATE_RSA_KEY_PAIR();
        Scope scope =
                new Scope(
                        OIDCScopeValue.OPENID.getValue(), OIDCScopeValue.OFFLINE_ACCESS.getValue());
        registerUser(scope, new Subject());
        registerClientWithPrivateKeyJwtAuthentication(
                keyPair.getPublic(), scope, SubjectType.PUBLIC);
        var baseTokenRequest =
                constructBaseTokenRequest(
                        scope, Optional.empty(), Optional.empty(), Optional.of(CLIENT_ID));

        var response = makeTokenRequestWithPrivateKeyJWT(baseTokenRequest, keyPair.getPrivate());

        assertThat(response, hasStatus(200));
        JSONObject jsonResponse = JSONObjectUtils.parse(response.getBody());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getRefreshToken());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getBearerAccessToken());
        assertThat(
                OIDCTokenResponse.parse(jsonResponse)
                        .getOIDCTokens()
                        .getIDToken()
                        .getJWTClaimsSet()
                        .getSubject(),
                equalTo(userStore.getPublicSubjectIdForEmail(TEST_EMAIL)));

        AuditAssertionsHelper.assertNoTxmaAuditEventsReceived(txmaAuditQueue);
    }

    @Test
    void shouldReturnIdTokenWithPairwiseSubjectId() throws Exception {
        KeyPair keyPair = KeyPairHelper.GENERATE_RSA_KEY_PAIR();
        Scope scope =
                new Scope(
                        OIDCScopeValue.OPENID.getValue(), OIDCScopeValue.OFFLINE_ACCESS.getValue());
        registerUser(scope, new Subject());
        registerClientWithPrivateKeyJwtAuthentication(
                keyPair.getPublic(), scope, SubjectType.PAIRWISE);
        var baseTokenRequest =
                constructBaseTokenRequest(
                        scope, Optional.empty(), Optional.empty(), Optional.of(CLIENT_ID));

        var response = makeTokenRequestWithPrivateKeyJWT(baseTokenRequest, keyPair.getPrivate());
        assertThat(response, hasStatus(200));
        JSONObject jsonResponse = JSONObjectUtils.parse(response.getBody());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getRefreshToken());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getBearerAccessToken());
        assertThat(
                OIDCTokenResponse.parse(jsonResponse)
                        .getOIDCTokens()
                        .getIDToken()
                        .getJWTClaimsSet()
                        .getSubject(),
                not(equalTo(userStore.getPublicSubjectIdForEmail(TEST_EMAIL))));

        AuditAssertionsHelper.assertNoTxmaAuditEventsReceived(txmaAuditQueue);
    }

    @Test
    void shouldCallTokenResourceAndReturnIdentityClaims() throws Exception {
        KeyPair keyPair = KeyPairHelper.GENERATE_RSA_KEY_PAIR();
        Scope scope = new Scope(OIDCScopeValue.OPENID.getValue());
        var claimsSetRequest = new ClaimsSetRequest().add("nickname").add("birthdate");
        var oidcClaimsRequest = new OIDCClaimsRequest().withUserInfoClaimsRequest(claimsSetRequest);
        registerUser(scope, new Subject());
        registerClientWithPrivateKeyJwtAuthentication(
                keyPair.getPublic(), scope, SubjectType.PAIRWISE);
        var baseTokenRequest =
                constructBaseTokenRequest(
                        scope,
                        Optional.of("P2.Cl.Cm"),
                        Optional.of(oidcClaimsRequest),
                        Optional.of(CLIENT_ID));

        var response = makeTokenRequestWithPrivateKeyJWT(baseTokenRequest, keyPair.getPrivate());

        assertThat(response, hasStatus(200));
        JSONObject jsonResponse = JSONObjectUtils.parse(response.getBody());
        assertNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getRefreshToken());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getBearerAccessToken());
        BearerAccessToken bearerAccessToken =
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getBearerAccessToken();
        JSONArray jsonarray =
                JSONArrayUtils.parse(
                        new Gson()
                                .toJson(
                                        SignedJWT.parse(bearerAccessToken.getValue())
                                                .getJWTClaimsSet()
                                                .getClaim("claims")));

        assertTrue(jsonarray.contains("nickname"));
        assertTrue(jsonarray.contains("birthdate"));
        AuditAssertionsHelper.assertNoTxmaAuditEventsReceived(txmaAuditQueue);
    }

    @Test
    void shouldCallTokenResourceAndOnlyReturnAccessTokenWithoutOfflineAccessScope()
            throws Exception {
        KeyPair keyPair = KeyPairHelper.GENERATE_RSA_KEY_PAIR();
        Scope scope = new Scope(OIDCScopeValue.OPENID.getValue());
        registerUser(scope, new Subject());
        registerClientWithPrivateKeyJwtAuthentication(
                keyPair.getPublic(), scope, SubjectType.PAIRWISE);
        var baseTokenRequest =
                constructBaseTokenRequest(
                        scope, Optional.empty(), Optional.empty(), Optional.of(CLIENT_ID));

        var response = makeTokenRequestWithPrivateKeyJWT(baseTokenRequest, keyPair.getPrivate());

        assertThat(response, hasStatus(200));
        JSONObject jsonResponse = JSONObjectUtils.parse(response.getBody());
        assertNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getRefreshToken());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getBearerAccessToken());

        AuditAssertionsHelper.assertNoTxmaAuditEventsReceived(txmaAuditQueue);
    }

    @Test
    void shouldCallTokenResourceWithRefreshTokenGrantAndReturn200() throws Exception {
        Scope scope =
                new Scope(
                        OIDCScopeValue.OPENID, OIDCScopeValue.EMAIL, OIDCScopeValue.OFFLINE_ACCESS);
        Subject publicSubject = new Subject();
        Subject internalSubject = new Subject();
        KeyPair keyPair = KeyPairHelper.GENERATE_RSA_KEY_PAIR();
        registerUser(scope, internalSubject);
        registerClientWithPrivateKeyJwtAuthentication(
                keyPair.getPublic(), scope, SubjectType.PAIRWISE);
        SignedJWT signedJWT = generateSignedRefreshToken(scope, publicSubject);
        RefreshToken refreshToken = new RefreshToken(signedJWT.serialize());
        RefreshTokenStore tokenStore =
                new RefreshTokenStore(refreshToken.getValue(), internalSubject.getValue());
        redis.addToRedis(
                REFRESH_TOKEN_PREFIX + signedJWT.getJWTClaimsSet().getJWTID(),
                objectMapper.writeValueAsString(tokenStore),
                900L);
        PrivateKey privateKey = keyPair.getPrivate();
        JWTAuthenticationClaimsSet claimsSet =
                new JWTAuthenticationClaimsSet(
                        new ClientID(CLIENT_ID), new Audience(ROOT_RESOURCE_URL + TOKEN_ENDPOINT));
        var expiryDate = NowHelper.nowPlus(5, ChronoUnit.MINUTES);
        claimsSet.getExpirationTime().setTime(expiryDate.getTime());
        var privateKeyJWT =
                new PrivateKeyJWT(claimsSet, JWSAlgorithm.RS256, privateKey, null, null);
        Map<String, List<String>> customParams = new HashMap<>();
        customParams.put(
                "grant_type", Collections.singletonList(GrantType.REFRESH_TOKEN.getValue()));
        customParams.put("client_id", Collections.singletonList(CLIENT_ID));
        customParams.put("refresh_token", Collections.singletonList(refreshToken.getValue()));
        Map<String, List<String>> privateKeyParams = privateKeyJWT.toParameters();
        privateKeyParams.putAll(customParams);
        String requestParams = URLUtils.serializeParameters(privateKeyParams);
        var response = makeRequest(Optional.of(requestParams), Map.of(), Map.of());

        assertThat(response, hasStatus(200));
        JSONObject jsonResponse = JSONObjectUtils.parse(response.getBody());

        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getRefreshToken());
        assertNotNull(
                TokenResponse.parse(jsonResponse)
                        .toSuccessResponse()
                        .getTokens()
                        .getBearerAccessToken());

        AuditAssertionsHelper.assertNoTxmaAuditEventsReceived(txmaAuditQueue);
    }

    private SignedJWT generateSignedRefreshToken(Scope scope, Subject publicSubject) {
        Date expiryDate = NowHelper.nowPlus(60, ChronoUnit.MINUTES);
        JWTClaimsSet claimsSet =
                new JWTClaimsSet.Builder()
                        .claim("scope", scope.toStringList())
                        .issuer("issuer-id")
                        .expirationTime(expiryDate)
                        .issueTime(NowHelper.now())
                        .claim("client_id", CLIENT_ID)
                        .subject(publicSubject.getValue())
                        .jwtID(IdGenerator.generate())
                        .build();
        return tokenSigner.signJwt(claimsSet);
    }

    private void registerClientWithPrivateKeyJwtAuthentication(
            PublicKey publicKey, Scope scope, SubjectType subjectType) {
        clientStore.registerClient(
                CLIENT_ID,
                "test-client",
                singletonList(REDIRECT_URI),
                singletonList(TEST_EMAIL),
                scope.toStringList(),
                Base64.getMimeEncoder().encodeToString(publicKey.getEncoded()),
                singletonList("https://localhost/post-logout-redirect"),
                "https://example.com",
                String.valueOf(ServiceType.MANDATORY),
                "https://test.com",
                subjectType.toString(),
                true,
                ClientType.WEB,
                true,
                null,
                ClientAuthenticationMethod.PRIVATE_KEY_JWT.getValue());
    }

    private void registerClientSecretClient(
            String clientSecret,
            ClientAuthenticationMethod clientAuthenticationMethod,
            Scope scope) {
        clientStore.registerClient(
                CLIENT_ID,
                "test-client",
                singletonList(REDIRECT_URI),
                singletonList(TEST_EMAIL),
                scope.toStringList(),
                null,
                singletonList("https://localhost/post-logout-redirect"),
                "https://example.com",
                String.valueOf(ServiceType.MANDATORY),
                "https://test.com",
                "pairwise",
                true,
                ClientType.WEB,
                true,
                clientSecret,
                clientAuthenticationMethod.getValue());
    }

    private void registerUser(Scope scope, Subject internalSubject) {
        userStore.signUp(TEST_EMAIL, "password-1", internalSubject);
        Set<String> claims = ValidScopes.getClaimsForListOfScopes(scope.toStringList());
        ClientConsent clientConsent =
                new ClientConsent(
                        CLIENT_ID, claims, LocalDateTime.now(ZoneId.of("UTC")).toString());
        userStore.updateConsent(TEST_EMAIL, clientConsent);
    }

    private AuthenticationRequest generateAuthRequest(
            Scope scope, Optional<String> vtr, Optional<OIDCClaimsRequest> claimsRequest) {
        ResponseType responseType = new ResponseType(ResponseType.Value.CODE);
        State state = new State();
        Nonce nonce = new Nonce();
        AuthenticationRequest.Builder builder =
                new AuthenticationRequest.Builder(
                                responseType,
                                scope,
                                new ClientID(CLIENT_ID),
                                URI.create("http://localhost/redirect"))
                        .state(state)
                        .nonce(nonce);
        claimsRequest.ifPresent(builder::claims);
        vtr.ifPresent(v -> builder.customParameter("vtr", v));

        return builder.build();
    }

    private APIGatewayProxyResponseEvent makeTokenRequestWithPrivateKeyJWT(
            Map<String, List<String>> requestParams, PrivateKey privateKey) throws JOSEException {
        var expiryDate = NowHelper.nowPlus(5, ChronoUnit.MINUTES);
        var claimsSet =
                new JWTAuthenticationClaimsSet(
                        new ClientID(CLIENT_ID), new Audience(ROOT_RESOURCE_URL + TOKEN_ENDPOINT));
        claimsSet.getExpirationTime().setTime(expiryDate.getTime());
        var privateKeyJWT =
                new PrivateKeyJWT(claimsSet, JWSAlgorithm.RS256, privateKey, null, null);
        requestParams.putAll(privateKeyJWT.toParameters());

        var requestBody = URLUtils.serializeParameters(requestParams);
        return makeRequest(Optional.of(requestBody), Map.of(), Map.of());
    }

    private APIGatewayProxyResponseEvent makeTokenRequestWithClientSecretBasic(
            Map<String, List<String>> requestParams, Secret clientSecret) {
        var clientSecretBasic = new ClientSecretBasic(new ClientID(CLIENT_ID), clientSecret);

        var requestBody = URLUtils.serializeParameters(requestParams);
        return makeRequest(
                Optional.of(requestBody),
                Map.of("Authorization", clientSecretBasic.toHTTPAuthorizationHeader()),
                Map.of());
    }

    private APIGatewayProxyResponseEvent makeTokenRequestWithClientSecretPost(
            Map<String, List<String>> requestParams, Secret clientSecret) {
        var clientSecretPost = new ClientSecretPost(new ClientID(CLIENT_ID), clientSecret);
        clientSecretPost.toParameters();
        requestParams.putAll(clientSecretPost.toParameters());
        var requestBody = URLUtils.serializeParameters(requestParams);
        return makeRequest(Optional.of(requestBody), Map.of(), Map.of());
    }

    private Map<String, List<String>> constructBaseTokenRequest(
            Scope scope,
            Optional<String> vtr,
            Optional<OIDCClaimsRequest> oidcClaimsRequest,
            Optional<String> clientId)
            throws Json.JsonException {
        String code = new AuthorizationCode().toString();
        VectorOfTrust vectorOfTrust = VectorOfTrust.getDefaults();
        if (vtr.isPresent()) {
            vectorOfTrust =
                    VectorOfTrust.parseFromAuthRequestAttribute(
                            singletonList(JsonArrayHelper.jsonArrayOf(vtr.get())));
        }
        redis.addAuthCodeAndCreateClientSession(
                code,
                "a-client-session-id",
                TEST_EMAIL,
                generateAuthRequest(scope, vtr, oidcClaimsRequest).toParameters(),
                vectorOfTrust,
                "client-name");
        Map<String, List<String>> customParams = new HashMap<>();
        customParams.put(
                "grant_type", Collections.singletonList(GrantType.AUTHORIZATION_CODE.getValue()));
        clientId.map(cid -> customParams.put("client_id", Collections.singletonList(cid)));
        customParams.put("code", Collections.singletonList(code));
        customParams.put("redirect_uri", Collections.singletonList(REDIRECT_URI));
        return customParams;
    }
}
