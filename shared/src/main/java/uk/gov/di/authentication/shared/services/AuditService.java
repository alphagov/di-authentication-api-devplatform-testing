package uk.gov.di.authentication.shared.services;

import com.amazonaws.services.kms.model.SignRequest;
import com.amazonaws.services.kms.model.SigningAlgorithmSpec;
import com.google.protobuf.ByteString;
import uk.gov.di.audit.AuditPayload.AuditEvent;
import uk.gov.di.audit.AuditPayload.SignedAuditEvent;
import uk.gov.di.authentication.shared.domain.AuditableEvent;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

public class AuditService {

    private final Clock clock;
    private final SnsService snsService;
    private final KmsConnectionService kmsConnectionService;

    public AuditService(
            Clock clock, SnsService snsService, KmsConnectionService kmsConnectionService) {
        this.clock = clock;
        this.snsService = snsService;
        this.kmsConnectionService = kmsConnectionService;
    }

    public AuditService() {
        this.clock = Clock.systemUTC();
        var configService = new ConfigurationService();
        this.snsService = new SnsService(configService);
        this.kmsConnectionService =
                new KmsConnectionService(
                        configService.getLocalstackEndpointUri(),
                        configService.getAwsRegion(),
                        configService.getAuditSigningKeyAlias());
    }

    public void submitAuditEvent(
            AuditableEvent event,
            String requestId,
            String sessionId,
            String clientId,
            MetadataPair... metadataPairs) {
        snsService.publishAuditMessage(
                generateLogLine(event, requestId, sessionId, clientId, metadataPairs));
    }

    String generateLogLine(
            AuditableEvent eventEnum,
            String requestId,
            String sessionId,
            String clientId,
            MetadataPair... metadataPairs) {
        var timestamp = clock.instant().toString();

        var auditEvent =
                AuditEvent.newBuilder()
                        .setEventName(eventEnum.toString())
                        .setTimestamp(timestamp)
                        .setRequestId(Optional.ofNullable(requestId).orElse(""))
                        .setSessionId(Optional.ofNullable(sessionId).orElse(""))
                        .setClientId(Optional.ofNullable(clientId).orElse(""))
                        .build();

        var signedEventBuilder =
                SignedAuditEvent.newBuilder()
                        .setSignature(ByteString.copyFrom(signPayload(auditEvent.toByteArray())))
                        .setPayload(auditEvent.toByteString());

        return new String(signedEventBuilder.build().toByteArray());
    }

    private byte[] signPayload(byte[] payload) {
        SignRequest signRequest = new SignRequest();
        signRequest.setKeyId(new ConfigurationService().getAuditSigningKeyAlias());
        signRequest.setMessage(ByteBuffer.wrap(payload));
        signRequest.setSigningAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256.toString());

        return kmsConnectionService.sign(signRequest).getSignature().array();
    }

    public static class MetadataPair {
        private final String key;
        private final Object value;

        private MetadataPair(String key, Object value) {
            this.key = key;
            this.value = value;
        }

        public static MetadataPair pair(String key, Object value) {
            return new MetadataPair(key, value);
        }

        @Override
        public String toString() {
            return String.format("[%s: %s]", key, value);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MetadataPair that = (MetadataPair) o;
            return Objects.equals(key, that.key) && Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }
    }
}
