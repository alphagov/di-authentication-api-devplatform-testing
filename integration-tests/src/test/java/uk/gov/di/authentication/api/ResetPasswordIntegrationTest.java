package uk.gov.di.authentication.api;

import com.nimbusds.oauth2.sdk.id.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.di.authentication.frontendapi.entity.ResetPasswordCompletionRequest;
import uk.gov.di.authentication.frontendapi.lambda.ResetPasswordHandler;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.entity.MFAMethodType;
import uk.gov.di.authentication.shared.entity.NotifyRequest;
import uk.gov.di.authentication.shared.serialization.Json;
import uk.gov.di.authentication.sharedtest.basetest.ApiGatewayHandlerIntegrationTest;
import uk.gov.di.authentication.sharedtest.extensions.CommonPasswordsExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uk.gov.di.authentication.frontendapi.domain.FrontendAuditableEvent.PASSWORD_RESET_SUCCESSFUL;
import static uk.gov.di.authentication.shared.entity.NotificationType.PASSWORD_RESET_CONFIRMATION;
import static uk.gov.di.authentication.shared.entity.NotificationType.PASSWORD_RESET_CONFIRMATION_SMS;
import static uk.gov.di.authentication.sharedtest.helper.AuditAssertionsHelper.assertTxmaAuditEventsReceived;
import static uk.gov.di.authentication.sharedtest.matchers.APIGatewayProxyResponseEventMatcher.hasStatus;

public class ResetPasswordIntegrationTest extends ApiGatewayHandlerIntegrationTest {

    private static final String EMAIL_ADDRESS = "test@test.com";
    private static final String PASSWORD = "Pa55word";
    private static final Subject SUBJECT = new Subject();

    @BeforeEach
    public void setUp() {
        handler = new ResetPasswordHandler(TXMA_ENABLED_CONFIGURATION_SERVICE);
        txmaAuditQueue.clear();
    }

    @Test
    void shouldUpdatePasswordAndReturn204() throws Json.JsonException {
        var sessionId = redis.createSession();
        userStore.signUp(EMAIL_ADDRESS, "password-1", SUBJECT);
        redis.addEmailToSession(sessionId, EMAIL_ADDRESS);

        var response =
                makeRequest(
                        Optional.of(new ResetPasswordCompletionRequest(PASSWORD)),
                        constructFrontendHeaders(sessionId),
                        Map.of());

        assertThat(response, hasStatus(204));

        List<NotifyRequest> requests = notificationsQueue.getMessages(NotifyRequest.class);

        assertThat(requests, hasSize(1));
        assertThat(requests.get(0).getDestination(), equalTo(EMAIL_ADDRESS));
        assertThat(requests.get(0).getNotificationType(), equalTo(PASSWORD_RESET_CONFIRMATION));

        assertTxmaAuditEventsReceived(txmaAuditQueue, List.of(PASSWORD_RESET_SUCCESSFUL));
    }

    @Test
    void shouldUpdatePasswordSendSMSAndWriteToAccountRecoveryTableWhenUserHasVerifiedPhoneNumber()
            throws Json.JsonException {
        var sessionId = redis.createSession();
        var phoneNumber = "+441234567890";
        userStore.signUp(EMAIL_ADDRESS, "password-1", SUBJECT);
        userStore.addPhoneNumber(EMAIL_ADDRESS, phoneNumber);
        redis.addEmailToSession(sessionId, EMAIL_ADDRESS);

        var response =
                makeRequest(
                        Optional.of(new ResetPasswordCompletionRequest(PASSWORD)),
                        constructFrontendHeaders(sessionId),
                        Map.of());

        assertThat(response, hasStatus(204));

        List<NotifyRequest> requests = notificationsQueue.getMessages(NotifyRequest.class);

        assertThat(requests, hasSize(2));
        assertThat(requests.get(0).getDestination(), equalTo(EMAIL_ADDRESS));
        assertThat(requests.get(0).getNotificationType(), equalTo(PASSWORD_RESET_CONFIRMATION));
        assertThat(requests.get(1).getDestination(), equalTo(phoneNumber));
        assertThat(requests.get(1).getNotificationType(), equalTo(PASSWORD_RESET_CONFIRMATION_SMS));

        assertTxmaAuditEventsReceived(txmaAuditQueue, List.of(PASSWORD_RESET_SUCCESSFUL));
    }

    @Test
    void shouldReturn400ForRequestWithCommonPassword() throws Json.JsonException {
        var sessionId = redis.createSession();
        userStore.signUp(EMAIL_ADDRESS, "password-1", SUBJECT);
        redis.addEmailToSession(sessionId, EMAIL_ADDRESS);

        var response =
                makeRequest(
                        Optional.of(
                                new ResetPasswordCompletionRequest(
                                        CommonPasswordsExtension.TEST_COMMON_PASSWORD)),
                        constructFrontendHeaders(sessionId),
                        Map.of());

        assertThat(response, hasStatus(400));
        assertTrue(response.getBody().contains(ErrorResponse.ERROR_1040.getMessage()));
    }

    private static Stream<Boolean> phoneNumberVerified() {
        return Stream.of(true, false);
    }

    @ParameterizedTest
    @MethodSource("phoneNumberVerified")
    void shouldUpdatePasswordAndWriteToAccountRecoveryTableWithIfUserHasVerifiedPhoneNumber(
            boolean phoneNumberVerified) throws Json.JsonException {
        var sessionId = redis.createSession();
        var phoneNumber = "+441234567890";
        userStore.signUp(EMAIL_ADDRESS, "password-1", SUBJECT);
        userStore.addPhoneNumber(EMAIL_ADDRESS, phoneNumber);
        userStore.setPhoneNumberVerified(EMAIL_ADDRESS, phoneNumberVerified);
        redis.addEmailToSession(sessionId, EMAIL_ADDRESS);

        var response =
                makeRequest(
                        Optional.of(new ResetPasswordCompletionRequest(PASSWORD)),
                        constructFrontendHeaders(sessionId),
                        Map.of());

        assertThat(response, hasStatus(204));

        List<NotifyRequest> requests = notificationsQueue.getMessages(NotifyRequest.class);

        assertThat(requests, hasSize(phoneNumberVerified ? 2 : 1));
        assertThat(requests.get(0).getDestination(), equalTo(EMAIL_ADDRESS));
        assertThat(requests.get(0).getNotificationType(), equalTo(PASSWORD_RESET_CONFIRMATION));
        assertTxmaAuditEventsReceived(txmaAuditQueue, List.of(PASSWORD_RESET_SUCCESSFUL));
        assertThat(
                accountRecoveryStore.isBlockPresent(EMAIL_ADDRESS), equalTo(phoneNumberVerified));

        if (phoneNumberVerified) {
            assertThat(requests.get(1).getDestination(), equalTo(phoneNumber));
            assertThat(
                    requests.get(1).getNotificationType(),
                    equalTo(PASSWORD_RESET_CONFIRMATION_SMS));
        }
    }

    private static Stream<Boolean> authAppVerified() {
        return Stream.of(true, false);
    }

    @ParameterizedTest
    @MethodSource("authAppVerified")
    void shouldUpdatePasswordAndWriteToAccountRecoveryTableWithIfUserHasVerifiedAuthApp(
            boolean authAppVerified) throws Json.JsonException {
        var sessionId = redis.createSession();
        userStore.signUp(EMAIL_ADDRESS, "password-1", SUBJECT);
        userStore.addMfaMethod(
                EMAIL_ADDRESS, MFAMethodType.AUTH_APP, authAppVerified, true, "credential");
        redis.addEmailToSession(sessionId, EMAIL_ADDRESS);

        var response =
                makeRequest(
                        Optional.of(new ResetPasswordCompletionRequest(PASSWORD)),
                        constructFrontendHeaders(sessionId),
                        Map.of());

        assertThat(response, hasStatus(204));

        List<NotifyRequest> requests = notificationsQueue.getMessages(NotifyRequest.class);

        assertThat(requests, hasSize(1));
        assertThat(requests.get(0).getDestination(), equalTo(EMAIL_ADDRESS));
        assertThat(requests.get(0).getNotificationType(), equalTo(PASSWORD_RESET_CONFIRMATION));
        assertThat(accountRecoveryStore.isBlockPresent(EMAIL_ADDRESS), equalTo(authAppVerified));

        assertTxmaAuditEventsReceived(txmaAuditQueue, List.of(PASSWORD_RESET_SUCCESSFUL));
    }
}
