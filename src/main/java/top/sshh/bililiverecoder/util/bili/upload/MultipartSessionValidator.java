package top.sshh.bililiverecoder.util.bili.upload;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MultipartSessionValidator {

    private MultipartSessionValidator() {
    }

    public static MetaBucketCheck checkMetaBucket(String uposUri, String metaUposUri) {
        String uposBucket = extractUposBucket(uposUri);
        String actualMetaBucket = extractUposBucket(metaUposUri);
        if (StringUtils.isBlank(uposBucket) || StringUtils.isBlank(actualMetaBucket)) {
            return new MetaBucketCheck(false, true, uposBucket, "", actualMetaBucket, "bucket unavailable");
        }
        String expectedMetaBucket = deriveMetaBucket(uposBucket);
        boolean consistent = StringUtils.equalsIgnoreCase(expectedMetaBucket, actualMetaBucket);
        return new MetaBucketCheck(true, consistent, uposBucket, expectedMetaBucket, actualMetaBucket,
                consistent ? "" : "meta bucket mismatch");
    }

    public static CompleteValidation validateCompleteContext(long expectedPartCount,
                                                             String uri,
                                                             String uploadToken,
                                                             long bizId,
                                                             String profile,
                                                             String initUploadId,
                                                             Map<Integer, String> etags,
                                                             Map<Integer, String> signedUploadIds,
                                                             Map<Integer, Integer> signedPartNumbers) {
        List<String> issues = new ArrayList<>();
        Set<String> uploadIdSet = new LinkedHashSet<>();
        int expected = expectedPartCount <= 0 ? 0 : (int) expectedPartCount;

        if (StringUtils.isBlank(uri)) {
            issues.add("uri missing");
        }
        if (StringUtils.isBlank(uploadToken)) {
            issues.add("upload_token missing");
        }
        if (StringUtils.isBlank(profile)) {
            issues.add("profile missing");
        }
        if (bizId < 0) {
            issues.add("biz_id invalid");
        }
        if (expected <= 0) {
            issues.add("expected part count invalid");
        }
        String inferredProfile = inferProfileFromUri(uri);
        if (StringUtils.isNotBlank(inferredProfile) && StringUtils.isNotBlank(profile)) {
            String profilePrefix = profilePrefix(profile);
            String inferredPrefix = profilePrefix(inferredProfile);
            if (StringUtils.isNotBlank(profilePrefix)
                    && StringUtils.isNotBlank(inferredPrefix)
                    && !StringUtils.equals(profilePrefix, inferredPrefix)) {
                issues.add("profile mismatch with uri bucket");
            }
        }

        int missingEtag = 0;
        int unquotedEtag = 0;
        int signedPartMismatch = 0;

        for (int i = 1; i <= expected; i++) {
            String etag = etags == null ? null : etags.get(i);
            if (StringUtils.isBlank(etag)) {
                missingEtag++;
            } else {
                String trimmed = etag.trim();
                if (!(trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
                    unquotedEtag++;
                }
            }

            String signedUploadId = signedUploadIds == null ? null : signedUploadIds.get(i);
            if (StringUtils.isNotBlank(signedUploadId)) {
                uploadIdSet.add(signedUploadId);
            }

            Integer signedPartNumber = signedPartNumbers == null ? null : signedPartNumbers.get(i);
            if (signedPartNumber != null && signedPartNumber > 0 && signedPartNumber != i) {
                signedPartMismatch++;
            }
        }

        if (missingEtag > 0) {
            issues.add("etag missing for " + missingEtag + " parts");
        }
        if (unquotedEtag > 0) {
            issues.add("etag unquoted for " + unquotedEtag + " parts");
        }
        if (signedPartMismatch > 0) {
            issues.add("signed partNumber mismatch for " + signedPartMismatch + " parts");
        }

        int signedUploadIdCount = uploadIdSet.size();
        if (signedUploadIdCount > 1) {
            issues.add("signed uploadId not unique");
        }

        String signedUploadId = signedUploadIdCount == 1 ? uploadIdSet.iterator().next() : "";
        boolean initUploadIdMismatch = signedUploadIdCount == 1
                && StringUtils.isNotBlank(initUploadId)
                && !StringUtils.equals(initUploadId, signedUploadId);
        String reason = issues.isEmpty() ? "" : String.join("; ", issues);
        return new CompleteValidation(issues.isEmpty(), reason, signedUploadIdCount, signedUploadId,
                uploadIdSet.isEmpty() ? "" : String.join(",", uploadIdSet), inferredProfile, initUploadIdMismatch);
    }

    public static String inferProfileFromUri(String uri) {
        String bucket = extractUposBucket(uri);
        if (StringUtils.isBlank(bucket)) {
            return "";
        }
        String lower = bucket.toLowerCase();
        if ("ugcever".equals(lower)) {
            return "ugce/bup";
        }
        if (lower.startsWith("ugc") && lower.endsWith("ever") && lower.length() > 7) {
            String segment = lower.substring(3, lower.length() - 4);
            if (StringUtils.isNotBlank(segment)) {
                return "ugc" + segment + "/bup";
            }
        }
        return "";
    }

    public static String preferProfileByUri(String candidateProfile, String multipartUri) {
        String inferred = inferProfileFromUri(multipartUri);
        if (StringUtils.isBlank(inferred)) {
            return candidateProfile;
        }
        if (StringUtils.isBlank(candidateProfile)) {
            return inferred;
        }
        String candidatePrefix = StringUtils.substringBefore(candidateProfile.toLowerCase(), "/");
        String inferredPrefix = StringUtils.substringBefore(inferred.toLowerCase(), "/");
        if (StringUtils.equals(candidatePrefix, inferredPrefix)) {
            return candidateProfile;
        }
        if ("ugcupos".equals(candidatePrefix) || "ugc".equals(candidatePrefix) || candidatePrefix.startsWith("ugc")) {
            return inferred;
        }
        return candidateProfile;
    }

    private static String profilePrefix(String profile) {
        return StringUtils.substringBefore(StringUtils.lowerCase(StringUtils.trimToEmpty(profile)), "/");
    }

    private static String deriveMetaBucket(String bucket) {
        if (StringUtils.isBlank(bucket)) {
            return "fxmetalf";
        }
        String lower = bucket.toLowerCase();
        if ("ugcever".equals(lower)) {
            return "emetalf";
        }
        if (lower.startsWith("ugc") && lower.endsWith("ever") && lower.length() > 7) {
            String segment = lower.substring(3, lower.length() - 4);
            if (StringUtils.isNotBlank(segment)) {
                return segment + "metalf";
            }
        }
        return "fxmetalf";
    }

    private static String extractUposBucket(String uposUri) {
        if (StringUtils.isBlank(uposUri)) {
            return "";
        }
        String raw = uposUri.trim();
        String withoutScheme = StringUtils.removeStart(raw, "upos://");
        int slash = withoutScheme.indexOf('/');
        String bucket = slash >= 0 ? withoutScheme.substring(0, slash) : withoutScheme;
        return StringUtils.trimToEmpty(bucket);
    }

    public static final class MetaBucketCheck {
        private final boolean comparable;
        private final boolean consistent;
        private final String uposBucket;
        private final String expectedMetaBucket;
        private final String actualMetaBucket;
        private final String reason;

        public MetaBucketCheck(boolean comparable,
                               boolean consistent,
                               String uposBucket,
                               String expectedMetaBucket,
                               String actualMetaBucket,
                               String reason) {
            this.comparable = comparable;
            this.consistent = consistent;
            this.uposBucket = uposBucket;
            this.expectedMetaBucket = expectedMetaBucket;
            this.actualMetaBucket = actualMetaBucket;
            this.reason = reason;
        }

        public boolean isComparable() {
            return comparable;
        }

        public boolean isConsistent() {
            return consistent;
        }

        public String getUposBucket() {
            return uposBucket;
        }

        public String getExpectedMetaBucket() {
            return expectedMetaBucket;
        }

        public String getActualMetaBucket() {
            return actualMetaBucket;
        }

        public String getReason() {
            return reason;
        }
    }

    public static final class CompleteValidation {
        private final boolean valid;
        private final String reason;
        private final int signedUploadIdCount;
        private final String signedUploadId;
        private final String signedUploadIds;
        private final String inferredProfile;
        private final boolean initUploadIdMismatch;

        public CompleteValidation(boolean valid,
                                  String reason,
                                  int signedUploadIdCount,
                                  String signedUploadId,
                                  String signedUploadIds,
                                  String inferredProfile,
                                  boolean initUploadIdMismatch) {
            this.valid = valid;
            this.reason = reason;
            this.signedUploadIdCount = signedUploadIdCount;
            this.signedUploadId = signedUploadId;
            this.signedUploadIds = signedUploadIds;
            this.inferredProfile = inferredProfile;
            this.initUploadIdMismatch = initUploadIdMismatch;
        }

        public boolean isValid() {
            return valid;
        }

        public String getReason() {
            return reason;
        }

        public int getSignedUploadIdCount() {
            return signedUploadIdCount;
        }

        public String getSignedUploadId() {
            return signedUploadId;
        }

        public String getSignedUploadIds() {
            return signedUploadIds;
        }

        public String getInferredProfile() {
            return inferredProfile;
        }

        public boolean isInitUploadIdMismatch() {
            return initUploadIdMismatch;
        }
    }
}
