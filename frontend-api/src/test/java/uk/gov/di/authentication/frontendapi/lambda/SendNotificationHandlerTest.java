package uk.gov.di.authentication.frontendapi.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.id.Subject;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import software.amazon.awssdk.core.exception.SdkClientException;
import uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent;
import uk.gov.di.authentication.shared.entity.ClientRegistry;
import uk.gov.di.authentication.shared.entity.ClientSession;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.NotificationType;
import uk.gov.di.authentication.shared.entity.NotifyRequest;
import uk.gov.di.authentication.shared.entity.Session;
import uk.gov.di.authentication.shared.helpers.ClientSubjectHelper;
import uk.gov.di.authentication.shared.helpers.IdGenerator;
import uk.gov.di.authentication.shared.helpers.LocaleHelper.SupportedLanguage;
import uk.gov.di.authentication.shared.helpers.PersistentIdHelper;
import uk.gov.di.authentication.shared.helpers.SaltHelper;
import uk.gov.di.authentication.shared.serialization.Json;
import uk.gov.di.authentication.shared.services.AuditService;
import uk.gov.di.authentication.shared.services.AuthenticationService;
import uk.gov.di.authentication.shared.services.AwsSqsClient;
import uk.gov.di.authentication.shared.services.ClientService;
import uk.gov.di.authentication.shared.services.ClientSessionService;
import uk.gov.di.authentication.shared.services.CodeGeneratorService;
import uk.gov.di.authentication.shared.services.CodeStorageService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.SerializationService;
import uk.gov.di.authentication.shared.services.SessionService;
import uk.gov.di.authentication.sharedtest.logging.CaptureLoggingExtension;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.ACCOUNT_RECOVERY_EMAIL_CODE_SENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.ACCOUNT_RECOVERY_EMAIL_CODE_SENT_FOR_TEST_CLIENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.ACCOUNT_RECOVERY_EMAIL_INVALID_CODE_REQUEST;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.EMAIL_CODE_SENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.EMAIL_CODE_SENT_FOR_TEST_CLIENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.EMAIL_INVALID_CODE_REQUEST;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.PHONE_CODE_SENT;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.PHONE_INVALID_CODE_REQUEST;
import static uk.gov.di.authentication.frontendapi.lambda.StartHandlerTest.CLIENT_SESSION_ID;
import static uk.gov.di.authentication.frontendapi.lambda.StartHandlerTest.CLIENT_SESSION_ID_HEADER;
import static uk.gov.di.authentication.frontendapi.lambda.StartHandlerTest.PERSISTENT_ID;
import static uk.gov.di.authentication.shared.entity.NotificationType.ACCOUNT_CREATED_CONFIRMATION;
import static uk.gov.di.authentication.shared.entity.NotificationType.VERIFY_CHANGE_HOW_GET_SECURITY_CODES;
import static uk.gov.di.authentication.shared.entity.NotificationType.VERIFY_EMAIL;
import static uk.gov.di.authentication.shared.entity.NotificationType.VERIFY_PHONE_NUMBER;
import static uk.gov.di.authentication.shared.services.CodeStorageService.CODE_BLOCKED_KEY_PREFIX;
import static uk.gov.di.authentication.shared.services.CodeStorageService.CODE_REQUEST_BLOCKED_KEY_PREFIX;
import static uk.gov.di.authentication.sharedtest.helper.RequestEventHelper.contextWithSourceIp;
import static uk.gov.di.authentication.sharedtest.logging.LogEventMatcher.withMessageContaining;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasJsonBody;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

class SendNotificationHandlerTest {

    private static final String TEST_EMAIL_ADDRESS = "joe.bloggs@digital.cabinet-office.gov.uk";
    private static final String TEST_PHONE_NUMBER = "07755551084";
    private static final String TEST_SIX_DIGIT_CODE = "123456";
    private static final long CODE_EXPIRY_TIME = 900;
    private static final long BLOCKED_EMAIL_DURATION = 799;
    private final String expectedCommonSubject =
            ClientSubjectHelper.calculatePairwiseIdentifier(
                    new Subject().getValue(), "test.account.gov.uk", SaltHelper.generateNewSalt());
    private static final String CLIENT_ID = "client-id";
    private static final String TEST_CLIENT_ID = "test-client-id";
    private static final URI REDIRECT_URI = URI.create("http://localhost/redirect");
    private final ConfigurationService configurationService = mock(ConfigurationService.class);
    private final AwsSqsClient awsSqsClient = mock(AwsSqsClient.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final CodeGeneratorService codeGeneratorService = mock(CodeGeneratorService.class);
    private final CodeStorageService codeStorageService = mock(CodeStorageService.class);
    private final ClientSessionService clientSessionService = mock(ClientSessionService.class);
    private final ClientService clientService = mock(ClientService.class);
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ClientSession clientSession = mock(ClientSession.class);
    private final ClientRegistry clientRegistry =
            new ClientRegistry().withTestClient(false).withClientID(CLIENT_ID);
    private final ClientRegistry testClientRegistry =
            new ClientRegistry()
                    .withTestClient(true)
                    .withClientID(TEST_CLIENT_ID)
                    .withTestClientEmailAllowlist(
                            List.of(
                                    "joe.bloggs@digital.cabinet-office.gov.uk",
                                    "jb2@digital.cabinet-office.gov.uk"));

    private final Context context = mock(Context.class);
    private static final Json objectMapper = SerializationService.getInstance();

    private final Session session =
            new Session(IdGenerator.generate())
                    .setEmailAddress(TEST_EMAIL_ADDRESS)
                    .setInternalCommonSubjectIdentifier(expectedCommonSubject);

    private final SendNotificationHandler handler =
            new SendNotificationHandler(
                    configurationService,
                    sessionService,
                    clientSessionService,
                    clientService,
                    authenticationService,
                    awsSqsClient,
                    codeGeneratorService,
                    codeStorageService,
                    auditService);

    @RegisterExtension
    private final CaptureLoggingExtension logging =
            new CaptureLoggingExtension(SendNotificationHandler.class);

    @AfterEach
    void tearDown() {
        assertThat(
                logging.events(),
                not(
                        hasItem(
                                withMessageContaining(
                                        session.getSessionId(),
                                        CLIENT_ID,
                                        TEST_CLIENT_ID,
                                        TEST_EMAIL_ADDRESS,
                                        TEST_PHONE_NUMBER))));
    }

    @BeforeEach
    void setup() {
        when(configurationService.getDefaultOtpCodeExpiry()).thenReturn(CODE_EXPIRY_TIME);
        when(configurationService.getEmailAccountCreationOtpCodeExpiry())
                .thenReturn(CODE_EXPIRY_TIME);
        when(configurationService.getBlockedEmailDuration()).thenReturn(BLOCKED_EMAIL_DURATION);
        when(codeGeneratorService.sixDigitCode()).thenReturn(TEST_SIX_DIGIT_CODE);
        when(configurationService.getCodeMaxRetries()).thenReturn(5);
        when(configurationService.getEnvironment()).thenReturn("unit-test");
        when(clientService.getClient(CLIENT_ID)).thenReturn(Optional.of(clientRegistry));
        when(clientService.getClient(TEST_CLIENT_ID)).thenReturn(Optional.of(testClientRegistry));
        when(clientSessionService.getClientSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(clientSession));
    }

    @ParameterizedTest
    @EnumSource(
            value = NotificationType.class,
            names = {"VERIFY_EMAIL", "VERIFY_CHANGE_HOW_GET_SECURITY_CODES"})
    void shouldReturn204ForValidVerifyEmailRequest(NotificationType notificationType)
            throws Json.JsonException {
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, notificationType));

        assertEquals(204, result.getStatusCode());
        verify(awsSqsClient)
                .send(
                        objectMapper.writeValueAsString(
                                new NotifyRequest(
                                        TEST_EMAIL_ADDRESS,
                                        notificationType,
                                        TEST_SIX_DIGIT_CODE,
                                        SupportedLanguage.EN)));
        verify(codeGeneratorService).sixDigitCode();
        verify(codeStorageService).getOtpCode(TEST_EMAIL_ADDRESS, notificationType);
        verify(codeStorageService)
                .saveOtpCode(
                        TEST_EMAIL_ADDRESS,
                        TEST_SIX_DIGIT_CODE,
                        CODE_EXPIRY_TIME,
                        notificationType);
        verify(sessionService).save(argThat(this::isSessionWithEmailSent));
        verify(auditService)
                .submitAuditEvent(
                        notificationType.equals(VERIFY_EMAIL)
                                ? EMAIL_CODE_SENT
                                : ACCOUNT_RECOVERY_EMAIL_CODE_SENT,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        PERSISTENT_ID);
    }

    @ParameterizedTest
    @EnumSource(
            value = NotificationType.class,
            names = {"VERIFY_EMAIL", "VERIFY_CHANGE_HOW_GET_SECURITY_CODES"})
    void shouldReturn204AndGenerateNewOtpCodeIfOneExistsWhenNewCodeRequested(
            NotificationType notificationType) throws Json.JsonException {
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\", \"requestNewCode\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, notificationType, true));

        assertThat(result, hasStatus(204));
        verify(codeGeneratorService).sixDigitCode();
        verify(codeStorageService, never()).getOtpCode(any(), any());
        verify(codeStorageService)
                .saveOtpCode(
                        TEST_EMAIL_ADDRESS,
                        TEST_SIX_DIGIT_CODE,
                        CODE_EXPIRY_TIME,
                        notificationType);
        verify(awsSqsClient)
                .send(
                        objectMapper.writeValueAsString(
                                new NotifyRequest(
                                        TEST_EMAIL_ADDRESS,
                                        notificationType,
                                        TEST_SIX_DIGIT_CODE,
                                        SupportedLanguage.EN)));
        verify(auditService)
                .submitAuditEvent(
                        notificationType.equals(VERIFY_EMAIL)
                                ? EMAIL_CODE_SENT
                                : ACCOUNT_RECOVERY_EMAIL_CODE_SENT,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        PERSISTENT_ID);
    }

    @Test
    void shouldReturn204AndUseExistingOtpCodeIfOneExistsForVerifyPhoneRequest()
            throws Json.JsonException {
        usingValidSession();
        usingValidClientSession(CLIENT_ID);
        when(codeStorageService.getOtpCode(any(String.class), any(NotificationType.class)))
                .thenReturn(Optional.of(TEST_SIX_DIGIT_CODE));

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\", \"phoneNumber\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, VERIFY_PHONE_NUMBER, TEST_PHONE_NUMBER));

        assertThat(result, hasStatus(204));
        verify(codeGeneratorService, never()).sixDigitCode();
        verify(codeStorageService, never())
                .saveOtpCode(
                        any(String.class),
                        any(String.class),
                        anyLong(),
                        any(NotificationType.class));
        verify(awsSqsClient)
                .send(
                        objectMapper.writeValueAsString(
                                new NotifyRequest(
                                        TEST_PHONE_NUMBER,
                                        VERIFY_PHONE_NUMBER,
                                        TEST_SIX_DIGIT_CODE,
                                        SupportedLanguage.EN)));
        verify(auditService)
                .submitAuditEvent(
                        PHONE_CODE_SENT,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        TEST_PHONE_NUMBER,
                        PERSISTENT_ID);
    }

    @ParameterizedTest
    @EnumSource(
            value = NotificationType.class,
            names = {"VERIFY_EMAIL", "VERIFY_CHANGE_HOW_GET_SECURITY_CODES"})
    void shouldReturn204AndNotPutMessageOnQueueForAValidRequestUsingTestClientWithAllowedEmail(
            NotificationType notificationType) {
        usingValidSession();
        usingValidClientSession(TEST_CLIENT_ID);
        when(configurationService.isTestClientsEnabled()).thenReturn(true);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, notificationType));

        assertEquals(204, result.getStatusCode());
        verifyNoInteractions(awsSqsClient);
        verify(codeStorageService).getOtpCode(TEST_EMAIL_ADDRESS, notificationType);
        verify(codeStorageService)
                .saveOtpCode(
                        TEST_EMAIL_ADDRESS,
                        TEST_SIX_DIGIT_CODE,
                        CODE_EXPIRY_TIME,
                        notificationType);
        verify(sessionService).save(argThat(this::isSessionWithEmailSent));
        verify(auditService)
                .submitAuditEvent(
                        notificationType.equals(VERIFY_EMAIL)
                                ? EMAIL_CODE_SENT_FOR_TEST_CLIENT
                                : ACCOUNT_RECOVERY_EMAIL_CODE_SENT_FOR_TEST_CLIENT,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        TEST_CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        PERSISTENT_ID);
    }

    @ParameterizedTest
    @EnumSource(
            value = NotificationType.class,
            names = {"VERIFY_EMAIL", "VERIFY_CHANGE_HOW_GET_SECURITY_CODES"})
    void shouldReturn400IfInvalidSessionProvided(NotificationType notificationType) {
        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, notificationType));

        assertEquals(400, result.getStatusCode());
        verifyNoInteractions(awsSqsClient);
        verifyNoInteractions(codeStorageService);
        verify(sessionService, never()).save(argThat(this::isSessionWithEmailSent));
        verifyNoInteractions(auditService);
    }

    @Test
    void shouldReturn400IfRequestIsMissingEmail() {
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result = sendRequest("{ }");

        assertEquals(400, result.getStatusCode());
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1001));
        verifyNoInteractions(awsSqsClient);
        verifyNoInteractions(codeStorageService);
        verifyNoInteractions(auditService);
    }

    @ParameterizedTest
    @EnumSource(
            value = NotificationType.class,
            names = {"VERIFY_EMAIL", "VERIFY_CHANGE_HOW_GET_SECURITY_CODES"})
    void shouldReturn500IfMessageCannotBeSentToQueue(NotificationType notificationType)
            throws Json.JsonException {
        usingValidSession();
        usingValidClientSession(CLIENT_ID);
        Mockito.doThrow(SdkClientException.class)
                .when(awsSqsClient)
                .send(
                        objectMapper.writeValueAsString(
                                new NotifyRequest(
                                        TEST_EMAIL_ADDRESS,
                                        notificationType,
                                        TEST_SIX_DIGIT_CODE,
                                        SupportedLanguage.EN)));

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, notificationType));

        assertEquals(500, result.getStatusCode());
        assertTrue(result.getBody().contains("Error sending message to queue"));
        verifyNoInteractions(auditService);
    }

    @Test
    void shouldReturn400WhenInvalidNotificationType() {
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, "VERIFY_PASSWORD"));

        assertEquals(400, result.getStatusCode());
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1001));

        verifyNoInteractions(awsSqsClient);
        verifyNoInteractions(auditService);
        verifyNoInteractions(codeStorageService);
    }

    @Test
    void shouldReturn204ForValidVerifyPhoneNumberRequest() throws Json.JsonException {
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\", \"phoneNumber\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, VERIFY_PHONE_NUMBER, TEST_PHONE_NUMBER));

        assertEquals(204, result.getStatusCode());
        verify(codeGeneratorService).sixDigitCode();
        verify(codeStorageService).getOtpCode(TEST_EMAIL_ADDRESS, VERIFY_PHONE_NUMBER);
        verify(codeStorageService)
                .saveOtpCode(
                        TEST_EMAIL_ADDRESS,
                        TEST_SIX_DIGIT_CODE,
                        CODE_EXPIRY_TIME,
                        VERIFY_PHONE_NUMBER);
        verify(awsSqsClient)
                .send(
                        objectMapper.writeValueAsString(
                                new NotifyRequest(
                                        TEST_PHONE_NUMBER,
                                        VERIFY_PHONE_NUMBER,
                                        TEST_SIX_DIGIT_CODE,
                                        SupportedLanguage.EN)));
        verify(auditService)
                .submitAuditEvent(
                        PHONE_CODE_SENT,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        TEST_PHONE_NUMBER,
                        PERSISTENT_ID);
    }

    @Test
    void shouldReturn400ForVerifyPhoneNumberRequestWhenPhoneNumberIsMissing() {
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, VERIFY_PHONE_NUMBER));

        assertEquals(400, result.getStatusCode());
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1011));
        verifyNoInteractions(awsSqsClient);
        verifyNoInteractions(auditService);
    }

    @ParameterizedTest
    @EnumSource(
            value = NotificationType.class,
            names = {"VERIFY_EMAIL", "VERIFY_CHANGE_HOW_GET_SECURITY_CODES"})
    void shouldReturn400IfUserHasReachedTheEmailCodeRequestLimit(
            NotificationType notificationType) {
        maxOutCodeRequestCount();
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, notificationType));

        assertEquals(400, result.getStatusCode());
        FrontendAuditableEvent expectedAuditableEvent = null;
        if (VERIFY_EMAIL.equals(notificationType)) {
            assertThat(result, hasJsonBody(ErrorResponse.ERROR_1029));
            expectedAuditableEvent = EMAIL_INVALID_CODE_REQUEST;
        } else if (VERIFY_CHANGE_HOW_GET_SECURITY_CODES.equals(notificationType)) {
            assertThat(result, hasJsonBody(ErrorResponse.ERROR_1046));
            expectedAuditableEvent = ACCOUNT_RECOVERY_EMAIL_INVALID_CODE_REQUEST;
        }
        verify(codeStorageService)
                .saveBlockedForEmail(
                        TEST_EMAIL_ADDRESS,
                        CODE_REQUEST_BLOCKED_KEY_PREFIX,
                        BLOCKED_EMAIL_DURATION);
        verify(codeStorageService, never())
                .saveOtpCode(
                        TEST_EMAIL_ADDRESS,
                        TEST_SIX_DIGIT_CODE,
                        CODE_EXPIRY_TIME,
                        notificationType);
        verifyNoInteractions(awsSqsClient);
        verify(auditService)
                .submitAuditEvent(
                        expectedAuditableEvent,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        PERSISTENT_ID);
    }

    @Test
    void shouldReturn400IfUserHasReachedThePhoneCodeRequestLimit() {
        maxOutCodeRequestCount();
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\",  \"phoneNumber\": \"%s\"  }",
                                TEST_EMAIL_ADDRESS, VERIFY_PHONE_NUMBER, TEST_PHONE_NUMBER));

        assertEquals(400, result.getStatusCode());
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1030));
        verify(codeStorageService)
                .saveBlockedForEmail(
                        TEST_EMAIL_ADDRESS,
                        CODE_REQUEST_BLOCKED_KEY_PREFIX,
                        BLOCKED_EMAIL_DURATION);
        verify(codeStorageService, never())
                .saveOtpCode(
                        TEST_EMAIL_ADDRESS,
                        TEST_SIX_DIGIT_CODE,
                        CODE_EXPIRY_TIME,
                        VERIFY_PHONE_NUMBER);
        verifyNoInteractions(awsSqsClient);
        verify(auditService)
                .submitAuditEvent(
                        PHONE_INVALID_CODE_REQUEST,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        TEST_PHONE_NUMBER,
                        PERSISTENT_ID);
    }

    @ParameterizedTest
    @EnumSource(
            value = NotificationType.class,
            names = {"VERIFY_EMAIL", "VERIFY_CHANGE_HOW_GET_SECURITY_CODES"})
    void shouldReturn400IfUserIsBlockedFromRequestingAnyMoreEmailOtpCodes(
            NotificationType notificationType) {
        when(codeStorageService.isBlockedForEmail(
                        TEST_EMAIL_ADDRESS, CODE_REQUEST_BLOCKED_KEY_PREFIX))
                .thenReturn(true);
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, notificationType));

        assertEquals(400, result.getStatusCode());
        FrontendAuditableEvent expectedAuditableEvent = null;
        if (VERIFY_EMAIL.equals(notificationType)) {
            assertThat(result, hasJsonBody(ErrorResponse.ERROR_1031));
            expectedAuditableEvent = EMAIL_INVALID_CODE_REQUEST;
        } else if (VERIFY_CHANGE_HOW_GET_SECURITY_CODES.equals(notificationType)) {
            assertThat(result, hasJsonBody(ErrorResponse.ERROR_1047));
            expectedAuditableEvent = ACCOUNT_RECOVERY_EMAIL_INVALID_CODE_REQUEST;
        }
        verifyNoInteractions(awsSqsClient);
        verify(auditService)
                .submitAuditEvent(
                        expectedAuditableEvent,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        PERSISTENT_ID);
    }

    @Test
    void shouldReturn400IfUserIsBlockedFromRequestingAnyMorePhoneOtpCodes() {
        when(codeStorageService.isBlockedForEmail(
                        TEST_EMAIL_ADDRESS, CODE_REQUEST_BLOCKED_KEY_PREFIX))
                .thenReturn(true);
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\",  \"phoneNumber\": \"%s\"  }",
                                TEST_EMAIL_ADDRESS, VERIFY_PHONE_NUMBER, TEST_PHONE_NUMBER));

        assertEquals(400, result.getStatusCode());
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1032));

        verifyNoInteractions(awsSqsClient);
        verify(auditService)
                .submitAuditEvent(
                        PHONE_INVALID_CODE_REQUEST,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        TEST_PHONE_NUMBER,
                        PERSISTENT_ID);
    }

    @ParameterizedTest
    @EnumSource(
            value = NotificationType.class,
            names = {"VERIFY_EMAIL", "VERIFY_CHANGE_HOW_GET_SECURITY_CODES"})
    void shouldReturn400IfUserIsBlockedFromEnteringEmailOtpCodes(
            NotificationType notificationType) {
        usingValidSession();
        usingValidClientSession(CLIENT_ID);
        when(codeStorageService.isBlockedForEmail(TEST_EMAIL_ADDRESS, CODE_BLOCKED_KEY_PREFIX))
                .thenReturn(true);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, notificationType));

        assertEquals(400, result.getStatusCode());
        if (VERIFY_EMAIL.equals(notificationType)) {
            assertThat(result, hasJsonBody(ErrorResponse.ERROR_1033));
        } else if (VERIFY_CHANGE_HOW_GET_SECURITY_CODES.equals(notificationType)) {
            assertThat(result, hasJsonBody(ErrorResponse.ERROR_1048));
        }
        var expectedAuditableEvent =
                notificationType.equals(VERIFY_EMAIL)
                        ? EMAIL_INVALID_CODE_REQUEST
                        : ACCOUNT_RECOVERY_EMAIL_INVALID_CODE_REQUEST;
        verifyNoInteractions(awsSqsClient);
        verify(auditService)
                .submitAuditEvent(
                        expectedAuditableEvent,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        PERSISTENT_ID);
    }

    @Test
    void shouldReturn400IfUserIsBlockedFromEnteringPhoneOtpCodes() {
        when(codeStorageService.isBlockedForEmail(TEST_EMAIL_ADDRESS, CODE_BLOCKED_KEY_PREFIX))
                .thenReturn(true);
        usingValidSession();
        usingValidClientSession(CLIENT_ID);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, VERIFY_PHONE_NUMBER));

        assertEquals(400, result.getStatusCode());
        assertThat(result, hasJsonBody(ErrorResponse.ERROR_1034));
        verifyNoInteractions(awsSqsClient);
        verify(auditService)
                .submitAuditEvent(
                        PHONE_INVALID_CODE_REQUEST,
                        CLIENT_SESSION_ID,
                        session.getSessionId(),
                        CLIENT_ID,
                        expectedCommonSubject,
                        TEST_EMAIL_ADDRESS,
                        "123.123.123.123",
                        AuditService.UNKNOWN,
                        PERSISTENT_ID);
    }

    @Test
    void shouldReturn204WhenSendingAccountCreationEmail() throws Json.JsonException {
        usingValidSession();
        usingValidClientSession(CLIENT_ID);
        var event = new APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of("Session-Id", session.getSessionId()));
        event.setBody(
                format(
                        "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                        TEST_EMAIL_ADDRESS, ACCOUNT_CREATED_CONFIRMATION));
        var result = handler.handleRequest(event, context);

        var notifyRequest =
                new NotifyRequest(
                        TEST_EMAIL_ADDRESS, ACCOUNT_CREATED_CONFIRMATION, SupportedLanguage.EN);
        verify(awsSqsClient).send(objectMapper.writeValueAsString(notifyRequest));
        verifyNoInteractions(codeStorageService);
        verifyNoInteractions(auditService);

        assertEquals(204, result.getStatusCode());
    }

    @Test
    void shouldReturn204AndNotSendAccountCreationEmailForTestClientAndTestUser() {
        usingValidSession();
        usingValidClientSession(TEST_CLIENT_ID);
        when(configurationService.isTestClientsEnabled()).thenReturn(true);

        var result =
                sendRequest(
                        format(
                                "{ \"email\": \"%s\", \"notificationType\": \"%s\" }",
                                TEST_EMAIL_ADDRESS, ACCOUNT_CREATED_CONFIRMATION));

        assertEquals(204, result.getStatusCode());
        verifyNoInteractions(awsSqsClient);
        verifyNoInteractions(auditService);
    }

    private APIGatewayProxyResponseEvent sendRequest(String body) {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put(PersistentIdHelper.PERSISTENT_ID_HEADER_NAME, PERSISTENT_ID);
        headers.put("Session-Id", session.getSessionId());
        headers.put(CLIENT_SESSION_ID_HEADER, CLIENT_SESSION_ID);
        event.setHeaders(headers);
        event.setRequestContext(contextWithSourceIp("123.123.123.123"));
        event.setBody(body);

        return handler.handleRequest(event, context);
    }

    private void maxOutCodeRequestCount() {
        session.resetCodeRequestCount();
        session.incrementCodeRequestCount();
        session.incrementCodeRequestCount();
        session.incrementCodeRequestCount();
        session.incrementCodeRequestCount();
        session.incrementCodeRequestCount();
    }

    private void usingValidSession() {
        when(sessionService.getSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(session));
    }

    private void usingValidClientSession(String clientId) {
        var authRequest =
                new AuthenticationRequest.Builder(
                                new ResponseType(ResponseType.Value.CODE),
                                new Scope(OIDCScopeValue.OPENID),
                                new ClientID(clientId),
                                REDIRECT_URI)
                        .state(new State())
                        .nonce(new Nonce())
                        .build();
        when(clientSessionService.getClientSessionFromRequestHeaders(anyMap()))
                .thenReturn(Optional.of(clientSession));
        when(clientSession.getAuthRequestParams()).thenReturn(authRequest.toParameters());
    }

    private boolean isSessionWithEmailSent(Session session) {
        return session.getEmailAddress().equals(TEST_EMAIL_ADDRESS)
                && session.getCodeRequestCount() == 1;
    }
}
