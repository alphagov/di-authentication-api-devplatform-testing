package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.di.authentication.frontendapi.entity.LoginResponse;
import uk.gov.di.authentication.frontendapi.helpers.RedactPhoneNumberHelper;
import uk.gov.di.authentication.shared.entity.ClientSession;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.helpers.IdGenerator;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SessionService;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.di.authentication.shared.entity.SessionState.ACCOUNT_TEMPORARILY_LOCKED;
import static uk.gov.di.authentication.shared.entity.SessionState.AUTHENTICATION_REQUIRED;
import static uk.gov.di.authentication.shared.entity.SessionState.LOGGED_IN;
import static uk.gov.di.authentication.shared.entity.SessionState.NEW;
import static uk.gov.di.authentication.shared.matchers.APIGatewayProxyResponseEventMatcher.hasJsonBody;
import static uk.gov.di.authentication.shared.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

class LoginHandlerTest {

    private static final String EMAIL = "computer-1";
    private static final String PASSWORD = "joe.bloggs@test.com";
    private static final String PHONE_NUMBER = "01234567890";
    private LoginHandler handler;
    private final Context context = mock(Context.class);
    private final ConfigurationService configurationService = mock(ConfigurationService.class);
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private final CodeStorageService codeStorageService = mock(CodeStorageService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final ClientSessionService clientSessionService = mock(ClientSessionService.class);
    private final ClientSession clientSession = mock(ClientSession.class);
    private final ClientService clientService = mock(ClientService.class);

    private final Session session =
            new Session(IdGenerator.generate()).setState(AUTHENTICATION_REQUIRED);

    @BeforeEach
    public void setUp() {
        when(clientSessionService.getClientSessionFromRequestHeaders(any()))
                .thenReturn(Optional.of(clientSession));
        handler =
                new LoginHandler(
                        configurationService,
                        sessionService,
                        authenticationService,
                        clientSessionService,
                        clientService,
                        codeStorageService);
    }

    @Test
    public void shouldReturn200IfLoginIsSuccessful() throws JsonProcessingException {
        when(authenticationService.userExists(EMAIL)).thenReturn(true);
        when(authenticationService.login(EMAIL, PASSWORD)).thenReturn(true);
        when(authenticationService.getPhoneNumber(EMAIL)).thenReturn(Optional.of(PHONE_NUMBER));
        when(clientSession.getAuthRequestParams())
                .thenReturn(generateAuthRequest(Optional.empty()).toParameters());
        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(format("{ \"password\": \"%s\", \"email\": \"%s\" }", PASSWORD, EMAIL));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(200));

        LoginResponse response =
                new ObjectMapper().readValue(result.getBody(), LoginResponse.class);
        assertThat(response.getSessionState(), equalTo(LOGGED_IN));
        assertThat(
                response.getRedactedPhoneNumber(),
                equalTo(RedactPhoneNumberHelper.redactPhoneNumber(PHONE_NUMBER)));
    }

    @Test
    public void stateChangesToAccountLockedAfter5Attempts() throws JsonProcessingException {
        when(configurationService.getMaxPasswordRetries()).thenReturn(5);
        when(authenticationService.userExists(EMAIL)).thenReturn(true);
        when(authenticationService.getPhoneNumber(EMAIL)).thenReturn(Optional.of(PHONE_NUMBER));
        when(authenticationService.login(EMAIL, PASSWORD)).thenReturn(false);
        when(codeStorageService.hasEnteredPasswordIncorrectBefore(EMAIL)).thenReturn(true);
        when(codeStorageService.getIncorrectPasswordCount(EMAIL)).thenReturn(5);

        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(format("{ \"password\": \"%s\", \"email\": \"%s\" }", PASSWORD, EMAIL));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(200));

        LoginResponse response =
                new ObjectMapper().readValue(result.getBody(), LoginResponse.class);
        assertThat(response.getSessionState(), equalTo(ACCOUNT_TEMPORARILY_LOCKED));
    }

    @Test
    public void incorrectPasswordCountRemovesUponLogin() throws JsonProcessingException {
        when(configurationService.getMaxPasswordRetries()).thenReturn(5);
        when(authenticationService.userExists(EMAIL)).thenReturn(true);
        when(authenticationService.getPhoneNumber(EMAIL)).thenReturn(Optional.of(PHONE_NUMBER));
        when(authenticationService.login(EMAIL, PASSWORD)).thenReturn(false);
        when(codeStorageService.hasEnteredPasswordIncorrectBefore(EMAIL)).thenReturn(true);
        when(codeStorageService.getIncorrectPasswordCount(EMAIL)).thenReturn(4);

        usingValidSession();
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(format("{ \"password\": \"%s\", \"email\": \"%s\" }", PASSWORD, EMAIL));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        when(authenticationService.login(EMAIL, PASSWORD)).thenReturn(true);
        when(codeStorageService.hasEnteredPasswordIncorrectBefore(EMAIL)).thenReturn(true);
        when(clientSession.getAuthRequestParams())
                .thenReturn(generateAuthRequest(Optional.empty()).toParameters());

        APIGatewayProxyResponseEvent result2 = handler.handleRequest(event, context);

        assertThat(result2, hasStatus(200));

        LoginResponse response =
                new ObjectMapper().readValue(result2.getBody(), LoginResponse.class);
        assertThat(response.getSessionState(), equalTo(LOGGED_IN));
    }

    @Test
    public void shouldReturn401IfUserHasInvalidCredentials() {
        when(authenticationService.userExists(EMAIL)).thenReturn(true);
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(format("{ \"password\": \"%s\", \"email\": \"%s\" }", PASSWORD, EMAIL));
        when(authenticationService.login(EMAIL, PASSWORD)).thenReturn(false);
        usingValidSession();

        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(401));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1008));
    }

    @Test
    public void shouldReturn400IfAnyRequestParametersAreMissing() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(format("{ \"password\": \"%s\"}", PASSWORD));

        when(authenticationService.login(EMAIL, PASSWORD)).thenReturn(false);
        usingValidSession();
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1001));
    }

    @Test
    public void shouldReturn400IfSessionIdIsInvalid() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(format("{ \"password\": \"%s\"}", PASSWORD));

        when(authenticationService.login(EMAIL, PASSWORD)).thenReturn(false);
        when(sessionService.getSessionFromRequestHeaders(event.getHeaders()))
                .thenReturn(Optional.empty());

        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1000));
    }

    @Test
    public void shouldReturn400IfUserDoesNotHaveAnAccount() {
        when(authenticationService.userExists(EMAIL)).thenReturn(false);
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(format("{ \"password\": \"%s\", \"email\": \"%s\" }", PASSWORD, EMAIL));
        when(authenticationService.login(EMAIL, PASSWORD)).thenReturn(false);
        usingValidSession();

        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1010));
    }

    @Test
    public void shouldReturn400IfUserTransitionsFromWrongState() {
        when(authenticationService.userExists(EMAIL)).thenReturn(true);
        when(authenticationService.login(EMAIL, PASSWORD)).thenReturn(true);
        when(authenticationService.getPhoneNumber(EMAIL)).thenReturn(Optional.of(PHONE_NUMBER));

        session.setState(NEW);

        usingValidSession();

        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(format("{ \"password\": \"%s\", \"email\": \"%s\" }", PASSWORD, EMAIL));
        APIGatewayProxyResponseEvent result = handler.handleRequest(event, context);

        assertThat(result, hasStatus(400));
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1017));
    }

    private AuthenticationRequest generateAuthRequest(Optional<String> credentialTrustLevel) {
        Scope scope = new Scope();
        scope.add(OIDCScopeValue.OPENID);
        AuthenticationRequest.Builder builder =
                new AuthenticationRequest.Builder(
                                ResponseType.CODE,
                                scope,
                                new ClientID(),
                                URI.create("http://localhost/redirect"))
                        .state(new State())
                        .nonce(new Nonce());

        credentialTrustLevel.ifPresent(t -> builder.customParameter("vtr", t));
        return builder.build();
    }

    private void usingValidSession() {
        when(sessionService.getSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(session));
    }
}
