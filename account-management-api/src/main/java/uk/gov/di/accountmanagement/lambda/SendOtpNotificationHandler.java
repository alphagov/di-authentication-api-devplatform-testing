package uk.gov.di.accountmanagement.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkClientException;
import uk.gov.di.accountmanagement.entity.NotifyRequest;
import uk.gov.di.accountmanagement.entity.SendNotificationRequest;
import uk.gov.di.accountmanagement.services.AwsSqsClient;
import uk.gov.di.accountmanagement.services.CodeStorageService;
import uk.gov.di.authentication.shared.entity.ErrorResponse;
import uk.gov.di.authentication.shared.services.CodeGeneratorService;
import uk.gov.di.authentication.shared.services.ConfigurationService;
import uk.gov.di.authentication.shared.services.DynamoService;
import uk.gov.di.authentication.shared.services.RedisConnectionService;
import uk.gov.di.authentication.shared.services.ValidationService;

import java.util.Optional;

import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1001;
import static uk.gov.di.authentication.shared.entity.ErrorResponse.ERROR_1002;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyErrorResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateApiGatewayProxyResponse;
import static uk.gov.di.authentication.shared.helpers.ApiGatewayResponseHelper.generateEmptySuccessApiGatewayResponse;
import static uk.gov.di.authentication.shared.helpers.WarmerHelper.isWarming;

public class SendOtpNotificationHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SendOtpNotificationHandler.class);

    private final ConfigurationService configurationService;
    private final ValidationService validationService;
    private final AwsSqsClient sqsClient;
    private final CodeGeneratorService codeGeneratorService;
    private final CodeStorageService codeStorageService;
    private final DynamoService dynamoService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SendOtpNotificationHandler(
            ConfigurationService configurationService,
            ValidationService validationService,
            AwsSqsClient sqsClient,
            CodeGeneratorService codeGeneratorService,
            CodeStorageService codeStorageService,
            DynamoService dynamoService) {
        this.configurationService = configurationService;
        this.validationService = validationService;
        this.sqsClient = sqsClient;
        this.codeGeneratorService = codeGeneratorService;
        this.codeStorageService = codeStorageService;
        this.dynamoService = dynamoService;
    }

    public SendOtpNotificationHandler() {
        this.configurationService = new ConfigurationService();
        this.sqsClient =
                new AwsSqsClient(
                        configurationService.getAwsRegion(),
                        configurationService.getEmailQueueUri(),
                        configurationService.getSqsEndpointUri());
        this.validationService = new ValidationService();
        this.codeGeneratorService = new CodeGeneratorService();
        this.codeStorageService =
                new CodeStorageService(new RedisConnectionService(configurationService));
        this.dynamoService = new DynamoService(configurationService);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {
        return isWarming(input)
                .orElseGet(
                        () -> {
                            LOGGER.info("Request received in SendOtp Lambda");
                            try {
                                SendNotificationRequest sendNotificationRequest =
                                        objectMapper.readValue(
                                                input.getBody(), SendNotificationRequest.class);
                                switch (sendNotificationRequest.getNotificationType()) {
                                    case VERIFY_EMAIL:
                                        LOGGER.info("NotificationType is VERIFY_EMAIL");
                                        Optional<ErrorResponse> emailErrorResponse =
                                                validationService.validateEmailAddress(
                                                        sendNotificationRequest.getEmail());
                                        if (emailErrorResponse.isPresent()) {
                                            LOGGER.error(
                                                    "Invalid email address. Errors are: {}",
                                                    emailErrorResponse.get());
                                            return generateApiGatewayProxyErrorResponse(
                                                    400, emailErrorResponse.get());
                                        }
                                        if (dynamoService.userExists(
                                                sendNotificationRequest.getEmail())) {
                                            LOGGER.error(
                                                    "User already exists with this email address");
                                            return generateApiGatewayProxyErrorResponse(
                                                    400, ErrorResponse.ERROR_1009);
                                        }
                                        return handleNotificationRequest(
                                                sendNotificationRequest.getEmail(),
                                                sendNotificationRequest);
                                    case VERIFY_PHONE_NUMBER:
                                        LOGGER.info("NotificationType is VERIFY_PHONE_NUMBER");
                                        Optional<ErrorResponse> phoneNumberValidationError =
                                                validationService.validatePhoneNumber(
                                                        sendNotificationRequest.getPhoneNumber());
                                        if (phoneNumberValidationError.isPresent()) {
                                            LOGGER.error(
                                                    "Invalid phone number. Errors are: {}",
                                                    phoneNumberValidationError.get());
                                            return generateApiGatewayProxyErrorResponse(
                                                    400, phoneNumberValidationError.get());
                                        }
                                        return handleNotificationRequest(
                                                sendNotificationRequest.getPhoneNumber(),
                                                sendNotificationRequest);
                                }
                                return generateApiGatewayProxyErrorResponse(400, ERROR_1002);
                            } catch (SdkClientException ex) {
                                LOGGER.error("Error sending message to queue", ex);
                                return generateApiGatewayProxyResponse(
                                        500, "Error sending message to queue");
                            } catch (JsonProcessingException e) {
                                LOGGER.error("Error parsing request", e);
                                return generateApiGatewayProxyErrorResponse(400, ERROR_1001);
                            }
                        });
    }

    private APIGatewayProxyResponseEvent handleNotificationRequest(
            String destination, SendNotificationRequest sendNotificationRequest)
            throws JsonProcessingException {

        String code = codeGeneratorService.sixDigitCode();
        NotifyRequest notifyRequest =
                new NotifyRequest(destination, sendNotificationRequest.getNotificationType(), code);
        codeStorageService.saveOtpCode(
                sendNotificationRequest.getEmail(),
                code,
                configurationService.getCodeExpiry(),
                sendNotificationRequest.getNotificationType());
        LOGGER.info(
                "Sending message to SQS queue for notificationType: {}",
                sendNotificationRequest.getNotificationType());
        sqsClient.send(serialiseRequest(notifyRequest));
        LOGGER.info("Generating successful API response");
        return generateEmptySuccessApiGatewayResponse();
    }

    private String serialiseRequest(Object request) throws JsonProcessingException {
        return objectMapper.writeValueAsString(request);
    }
}
