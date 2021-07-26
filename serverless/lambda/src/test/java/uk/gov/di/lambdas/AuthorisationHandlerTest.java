package uk.gov.di.lambdas;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.openid.connect.sdk.OIDCError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.entity.ClientSession;
import uk.gov.di.entity.Session;
import uk.gov.di.entity.SessionState;
import uk.gov.di.services.ClientService;
import uk.gov.di.services.ClientSessionService;
import uk.gov.di.services.ConfigurationService;
import uk.gov.di.services.SessionService;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.di.matchers.APIGatewayProxyResponseEventMatcher.hasBody;
import static uk.gov.di.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

class AuthorisationHandlerTest {

    private final Context context = mock(Context.class);
    private final ClientService clientService = mock(ClientService.class);
    private final ConfigurationService configService = mock(ConfigurationService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final ClientSessionService clientSessionService = mock(ClientSessionService.class);

    private static final String EXPECTED_COOKIE_STRING =
            "gs=a-session-id.client-session-id; Max-Age=1800; Domain=auth.ida.digital.cabinet-office.gov.uk; Secure; HttpOnly;";

    final String domainName = "auth.ida.digital.cabinet-office.gov.uk";

    private AuthorisationHandler handler;

    @BeforeEach
    public void setUp() {
        handler =
                new AuthorisationHandler(
                        clientService, configService, sessionService, clientSessionService);
    }

    @Test
    void shouldSetCookieAndRedirectToLoginOnSuccess() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(configService.getDomainName()).thenReturn(domainName);
        when(sessionService.createSession()).thenReturn(session);
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(1800);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setQueryStringParameters(
                Map.of(
                        "client_id", "test-id",
                        "redirect_uri", "http://localhost:8080",
                        "scope", "email,openid,profile",
                        "response_type", "code",
                        "state", "some-state"));
        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);
        URI uri = URI.create(response.getHeaders().get("Location"));
        final String expectedCookieString =
                "gs=a-session-id.client-session-id; Max-Age=1800; Domain=auth.ida.digital.cabinet-office.gov.uk; Secure; HttpOnly;";

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(expectedCookieString, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
    }

    @Test
    void shouldReturn400WhenAuthorisationRequestCannotBeParsed() {
        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setQueryStringParameters(
                Map.of(
                        "client_id", "test-id",
                        "redirect_uri", "http://localhost:8080",
                        "scope", "email,openid,profile",
                        "invalid_parameter", "nonsense"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        assertThat(response, hasStatus(400));
        assertThat(response, hasBody("Cannot parse authentication request"));
    }

    @Test
    void shouldReturn400WhenAuthorisationRequestContainsInvalidData() {
        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.of(OAuth2Error.INVALID_SCOPE));
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setQueryStringParameters(
                Map.of(
                        "client_id", "test-id",
                        "redirect_uri", "http://localhost:8080",
                        "scope", "email,openid,profile,non-existent-scope",
                        "response_type", "code"));

        APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);

        assertThat(response, hasStatus(302));
        assertEquals(
                "http://localhost:8080?error=invalid_scope&error_description=Invalid%2C+unknown+or+malformed+scope",
                response.getHeaders().get("Location"));
    }

    @Test
    void shouldDoLoginWhenPromptParamAbsentAndNotLoggedIn() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(sessionService.createSession()).thenReturn(session);
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(1800);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");
        when(configService.getDomainName()).thenReturn(domainName);

        APIGatewayProxyResponseEvent response = handler.handleRequest(withRequestEvent(), context);
        URI uri = URI.create(response.getHeaders().get("Location"));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.NEW, session.getState());
    }

    @Test
    void shouldSkipLoginWhenPromptParamAbsentAndLoggedIn() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");
        session.addClientSession("old-client-session-id");

        whenLoggedIn(session, loginUrl);

        APIGatewayProxyResponseEvent response = handler.handleRequest(withRequestEvent(), context);
        URI uri = URI.create(response.getHeaders().get("Location"));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.AUTHENTICATED, session.getState());
        assertThat(session.getClientSessions(), hasItem("client-session-id"));
        assertThat(session.getClientSessions(), hasSize(2));
    }

    @Test
    void shouldReturnErrorWhenPromptParamNoneAndNotLoggedIn() {
        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                handler.handleRequest(withPromptRequestEvent("none"), context);
        assertThat(response, hasStatus(302));
        assertThat(
                getHeaderValueByParamName(response, "Location"),
                containsString("error=login_required"));
    }

    @Test
    void shouldSkipLoginWhenPromptParamNoneAndLoggedIn() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");
        session.addClientSession("old-client-session-id");

        whenLoggedIn(session, loginUrl);

        APIGatewayProxyResponseEvent response =
                handler.handleRequest(withPromptRequestEvent("none"), context);
        URI uri = URI.create(response.getHeaders().get("Location"));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.AUTHENTICATED, session.getState());
        assertThat(session.getClientSessions(), hasItem("client-session-id"));
        assertThat(session.getClientSessions(), hasSize(2));
    }

    @Test
    void shouldDoLoginWhenPromptParamLoginAndNotLoggedIn() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(sessionService.createSession()).thenReturn(session);
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(1800);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");
        when(configService.getDomainName()).thenReturn(domainName);

        APIGatewayProxyResponseEvent response =
                handler.handleRequest(withPromptRequestEvent("login"), context);
        URI uri = URI.create(response.getHeaders().get("Location"));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());

        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));

        verify(sessionService).save(eq(session));
        assertEquals(SessionState.NEW, session.getState());
    }

    @Test
    void shouldDoLoginWhenPromptParamLoginAndLoggedIn() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        whenLoggedIn(session, loginUrl);

        APIGatewayProxyResponseEvent response =
                handler.handleRequest(withPromptRequestEvent("login"), context);
        URI uri = URI.create(response.getHeaders().get("Location"));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.AUTHENTICATION_REQUIRED, session.getState());
    }

    @Test
    void shouldReturnErrorWhenUnrecognisedPromptValue() {
        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                handler.handleRequest(withPromptRequestEvent("unrecognised"), context);
        assertThat(response, hasStatus(400));
        assertThat(response.getBody(), containsString("Cannot parse authentication request"));
    }

    @Test
    void shouldReturnErrorWhenPromptParamWithMultipleValuesNoneAndLogin() {
        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                handler.handleRequest(withPromptRequestEvent("none login"), context);
        assertThat(response, hasStatus(400));
        assertThat(response.getBody(), containsString("Cannot parse authentication request"));
    }

    @Test
    void shouldReturnErrorWhenPromptParamWithUnsupportedMultipleValues() {
        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                handler.handleRequest(withPromptRequestEvent("login consent"), context);
        assertThat(response, hasStatus(302));
        assertThat(
                getHeaderValueByParamName(response, "Location"),
                containsString(OIDCError.UNMET_AUTHENTICATION_REQUIREMENTS_CODE));
    }

    @Test
    void shouldReturnErrorWhenPromptParamConsent() {
        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                handler.handleRequest(withPromptRequestEvent("consent"), context);
        assertThat(response, hasStatus(302));
        assertThat(
                getHeaderValueByParamName(response, "Location"),
                containsString(OIDCError.UNMET_AUTHENTICATION_REQUIREMENTS_CODE));
    }

    @Test
    void shouldReturnErrorWhenPromptParamSelectAccount() {
        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        APIGatewayProxyResponseEvent response =
                handler.handleRequest(withPromptRequestEvent("select_account"), context);
        assertThat(response, hasStatus(302));
        assertThat(
                getHeaderValueByParamName(response, "Location"),
                containsString(OIDCError.UNMET_AUTHENTICATION_REQUIREMENTS_CODE));
    }

    @Test
    void shouldDoLoginWhenPromptParamAbsentAndNotLoggedInBecauseNoSession() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(sessionService.getSessionFromSessionCookie(any())).thenReturn(Optional.empty());
        when(sessionService.createSession()).thenReturn(session);
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(1800);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");
        when(configService.getDomainName()).thenReturn(domainName);

        APIGatewayProxyResponseEvent response = handler.handleRequest(withRequestEvent(), context);
        URI uri = URI.create(response.getHeaders().get("Location"));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.NEW, session.getState());
    }

    @Test
    void shouldDoLoginWhenPromptParamAbsentAndNotLoggedInBecauseSessionNotAuthenticated() {
        final URI loginUrl = URI.create("http://example.com");
        final Session session = new Session("a-session-id");

        whenLoggedIn(session, loginUrl);
        session.setState(SessionState.AUTHENTICATION_REQUIRED);

        APIGatewayProxyResponseEvent response = handler.handleRequest(withRequestEvent(), context);
        URI uri = URI.create(response.getHeaders().get("Location"));

        assertThat(response, hasStatus(302));
        assertEquals(loginUrl.getAuthority(), uri.getAuthority());
        assertEquals(EXPECTED_COOKIE_STRING, response.getHeaders().get("Set-Cookie"));
        verify(sessionService).save(eq(session));
        assertEquals(SessionState.AUTHENTICATION_REQUIRED, session.getState());
    }

    private APIGatewayProxyRequestEvent withPromptRequestEvent(String prompt) {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setQueryStringParameters(
                Map.of(
                        "client_id", "test-id",
                        "redirect_uri", "http://localhost:8080",
                        "scope", "email,openid,profile",
                        "response_type", "code",
                        "state", "some-state",
                        "prompt", prompt));
        return event;
    }

    private APIGatewayProxyRequestEvent withRequestEvent() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setQueryStringParameters(
                Map.of(
                        "client_id", "test-id",
                        "redirect_uri", "http://localhost:8080",
                        "scope", "email,openid,profile",
                        "response_type", "code",
                        "state", "some-state"));
        return event;
    }

    private void whenLoggedIn(Session session, URI loginUrl) {
        session.setState(SessionState.AUTHENTICATED);
        when(clientService.getErrorForAuthorizationRequest(any(AuthorizationRequest.class)))
                .thenReturn(Optional.empty());
        when(configService.getLoginURI()).thenReturn(loginUrl);
        when(sessionService.getSessionFromSessionCookie(any())).thenReturn(Optional.of(session));
        when(configService.getSessionCookieAttributes()).thenReturn("Secure; HttpOnly;");
        when(configService.getSessionCookieMaxAge()).thenReturn(1800);
        when(clientSessionService.generateClientSession(any(ClientSession.class)))
                .thenReturn("client-session-id");
        when(configService.getDomainName()).thenReturn(domainName);
    }

    private String getHeaderValueByParamName(
            APIGatewayProxyResponseEvent response, String paramName) {
        return response.getHeaders().get(paramName);
    }
}
