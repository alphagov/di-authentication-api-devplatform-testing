package uk.gov.di.authentication.shared.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import uk.gov.di.authentication.shared.dynamodb.DynamoDBItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@DynamoDbBean
public class UserCredentials implements DynamoDBItem {

    public static final String ATTRIBUTE_EMAIL = "Email";
    public static final String ATTRIBUTE_SUBJECT_ID = "SubjectID";
    public static final String ATTRIBUTE_PASSWORD = "Password";
    public static final String ATTRIBUTE_CREATED = "Created";
    public static final String ATTRIBUTE_UPDATED = "Updated";
    public static final String ATTRIBUTE_MIGRATED_PASSWORD = "MigratedPassword";
    public static final String ATTRIBUTE_MFA_METHODS = "MfaMethods";

    private String email;
    private String subjectID;
    private String password;
    private String created;
    private String updated;
    private String migratedPassword;
    private List<MFAMethod> mfaMethods;

    public UserCredentials() {}

    @DynamoDbPartitionKey
    @DynamoDbAttribute(ATTRIBUTE_EMAIL)
    public String getEmail() {
        return email;
    }

    public UserCredentials withEmail(String email) {
        this.email = email;
        return this;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setSubjectID(String subjectID) {
        this.subjectID = subjectID;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public void setUpdated(String updated) {
        this.updated = updated;
    }

    public void setMigratedPassword(String migratedPassword) {
        this.migratedPassword = migratedPassword;
    }

    public void setMfaMethods(List<MFAMethod> mfaMethods) {
        this.mfaMethods = mfaMethods;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"SubjectIDIndex"})
    @DynamoDbAttribute(ATTRIBUTE_SUBJECT_ID)
    public String getSubjectID() {
        return subjectID;
    }

    public UserCredentials withSubjectID(String subjectID) {
        this.subjectID = subjectID;
        return this;
    }

    @DynamoDbAttribute(ATTRIBUTE_PASSWORD)
    public String getPassword() {
        return password;
    }

    public UserCredentials withPassword(String password) {
        this.password = password;
        return this;
    }

    @DynamoDbAttribute(ATTRIBUTE_CREATED)
    public String getCreated() {
        return created;
    }

    public UserCredentials withCreated(String created) {
        this.created = created;
        return this;
    }

    @DynamoDbAttribute(ATTRIBUTE_UPDATED)
    public String getUpdated() {
        return updated;
    }

    public UserCredentials withUpdated(String updated) {
        this.updated = updated;
        return this;
    }

    @DynamoDbAttribute(ATTRIBUTE_MIGRATED_PASSWORD)
    public String getMigratedPassword() {
        return migratedPassword;
    }

    public UserCredentials withMigratedPassword(String migratedPassword) {
        this.migratedPassword = migratedPassword;
        return this;
    }

    @DynamoDbAttribute(ATTRIBUTE_MFA_METHODS)
    public List<MFAMethod> getMfaMethods() {
        return mfaMethods;
    }

    public void withMfaMethods(List<MFAMethod> mfaMethods) {
        this.mfaMethods = mfaMethods;
    }

    public UserCredentials setMfaMethod(MFAMethod mfaMethod) {
        if (this.mfaMethods == null) {
            this.mfaMethods = List.of(mfaMethod);
        } else {
            this.mfaMethods.removeIf(
                    t -> t.getMfaMethodType().equals(mfaMethod.getMfaMethodType()));
            this.mfaMethods.add(mfaMethod);
        }
        return this;
    }

    @Override
    public Map<String, AttributeValue> toItem() {
        Map<String, AttributeValue> attributes = new HashMap<>();
        if (getEmail() != null) attributes.put(ATTRIBUTE_EMAIL, AttributeValue.fromS(getEmail()));
        if (getSubjectID() != null)
            attributes.put(ATTRIBUTE_SUBJECT_ID, AttributeValue.fromS(getSubjectID()));
        if (getPassword() != null)
            attributes.put(ATTRIBUTE_PASSWORD, AttributeValue.fromS(getPassword()));
        if (getCreated() != null)
            attributes.put(ATTRIBUTE_CREATED, AttributeValue.fromS(getCreated()));
        if (getUpdated() != null)
            attributes.put(ATTRIBUTE_UPDATED, AttributeValue.fromS(getUpdated()));
        if (getMigratedPassword() != null)
            attributes.put(
                    ATTRIBUTE_MIGRATED_PASSWORD, AttributeValue.fromS(getMigratedPassword()));
        if (getMfaMethods() != null) {
            List<AttributeValue> methods = new ArrayList<>();
            getMfaMethods().forEach(m -> methods.add(m.toAttributeValue()));
            attributes.put(ATTRIBUTE_MFA_METHODS, AttributeValue.fromL(methods));
        }
        return attributes;
    }
}
