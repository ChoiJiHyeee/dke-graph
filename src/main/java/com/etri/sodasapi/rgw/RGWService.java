package com.etri.sodasapi.rgw;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.etri.sodasapi.common.*;
import com.etri.sodasapi.common.Quota;
import com.etri.sodasapi.config.Constants;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.twonote.rgwadmin4j.RgwAdmin;
import org.twonote.rgwadmin4j.RgwAdminBuilder;
import org.twonote.rgwadmin4j.model.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RGWService {
    private final Constants constants;
    private RgwAdmin rgwAdmin;

    private synchronized RgwAdmin getRgwAdmin() {
        if (this.rgwAdmin == null) {
            rgwAdmin = new RgwAdminBuilder().accessKey(constants.getRgwAdminAccess())
                    .secretKey(constants.getRgwAdminSecret())
                    .endpoint(constants.getRgwEndpoint() + "/admin")
                    .build();
        }
        return rgwAdmin;
    }


    public List<SBucket> getBuckets(Key key) {
        AmazonS3 conn = getClient(key);
        List<Bucket> buckets = conn.listBuckets();
        List<SBucket> bucketList = new ArrayList<>();

        for (Bucket mybucket : buckets) {
            bucketList.add(new SBucket(mybucket.getName(), mybucket.getCreationDate()));
        }
        return bucketList;
    }

    public List<BObject> getObjects(Key key, String bucketName) {
        AmazonS3 conn = getClient(key);
        ;
        ObjectListing objects = conn.listObjects(bucketName);

//        System.out.println(objects);
        List<BObject> objectList = new ArrayList<>();

        do {
            for (S3ObjectSummary objectSummary : objects.getObjectSummaries()) {
                objectList.add(new BObject(objectSummary.getKey(), objectSummary.getSize(), objectSummary.getLastModified()));
                System.out.println(objectSummary.getKey() + " " + conn.getObjectAcl(bucketName, objectSummary.getKey()));
            }
            objects = conn.listNextBatchOfObjects(objects);
        } while (objects.isTruncated());
        return objectList;
    }

    public Bucket createBucket(Key key, String bucketName) {
        AmazonS3 conn = getClient(key);
        Bucket newBucket = conn.createBucket(bucketName);
        return newBucket;

    }

    public void deleteBucket(Key key, String bucketName) {
        AmazonS3 conn = getClient(key);

        List<BObject> objectList = getObjects(key, bucketName);

        for (BObject bObject : objectList) {
            conn.deleteObject(bucketName, bObject.getObjectName());
        }

        conn.deleteBucket(bucketName);
    }

    public void deleteObject(Key key, String bucketName, String object) {
        AmazonS3 conn = getClient(key);

        conn.deleteObject(bucketName, object);
    }

    private synchronized AmazonS3 getClient(Key key) {
        AmazonS3 amazonS3;

        String accessKey = key.getAccessKey();
        String secretKey = key.getSecretKey();

        AWSCredentials awsCredentials = new BasicAWSCredentials(accessKey, secretKey);
        ClientConfiguration clientConfiguration = new ClientConfiguration();
        return amazonS3 = AmazonS3ClientBuilder
                .standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(constants.getRgwEndpoint(), Regions.DEFAULT_REGION.getName()))
                .withPathStyleAccessEnabled(true)
                .withClientConfiguration(clientConfiguration)
                .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
                .build();
    }

    public void objectUpload(MultipartFile file, String bucketName, Key key) throws IOException {
        AmazonS3 conn = getClient(key);
        ByteArrayInputStream input = new ByteArrayInputStream(file.getBytes());
        System.out.println(conn.putObject(bucketName, file.getOriginalFilename(), input, new ObjectMetadata()));
    }

    // TODO: 2023.7.22 Keycloak과 연동해 관리자 확인하는 코드 추가해야 함.
    public boolean validAccess(Key key) {
        return true;
    }

    public URL objectDownUrl(Key key, String bucketName, String object) {
        AmazonS3 conn = getClient(key);

        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, object);

        System.out.println(conn.generatePresignedUrl(request));
        return conn.generatePresignedUrl(request);
    }

    public Map<String, Long> getIndividualBucketQuota(String bucketName) {
        RgwAdmin rgwAdmin = getRgwAdmin();

        Optional<BucketInfo> bucketInfo = rgwAdmin.getBucketInfo(bucketName);
        BucketInfo bucketInfo1 = bucketInfo.get();

        Map<String, Long> individualBucketQuota = new HashMap<>();

        individualBucketQuota.put("max-size-kb", bucketInfo1.getBucketQuota().getMaxSizeKb());
        individualBucketQuota.put("max-objects", bucketInfo1.getBucketQuota().getMaxObjects());
        individualBucketQuota.put("actual-size", bucketInfo1.getUsage().getRgwMain().getSize_actual());

        return individualBucketQuota;
    }

    public void getBucketInfo(String bucketName){
        RgwAdmin rgwAdmin = getRgwAdmin();

        long usage =  rgwAdmin.getBucketInfo(bucketName).get().getUsage().getRgwMain().getSize();

        System.out.println(usage);
    }


    public Quota setIndividualBucketQuota(String uid, String bucketName, Quota quota){
        RgwAdmin rgwAdmin = getRgwAdmin();

        if(rgwAdmin.getUserQuota(uid).get().getMaxSizeKb() >= Long.parseLong(quota.getMax_size_kb())
            && rgwAdmin.getUserQuota(uid).get().getMaxObjects() >= Long.parseLong(quota.getMax_objects())){
            rgwAdmin.setIndividualBucketQuota(uid, bucketName, Long.parseLong(quota.getMax_objects()), Long.parseLong(quota.getMax_size_kb()));
        }

        return quota;
    }

    public Double quotaUtilizationInfo(String bucketName) {
        RgwAdmin rgwAdmin = getRgwAdmin();

        Optional<BucketInfo> bucketInfo = rgwAdmin.getBucketInfo(bucketName);
        BucketInfo bucketInfo1 = bucketInfo.get();
        return (((double) bucketInfo1.getUsage().getRgwMain().getSize_actual() / (bucketInfo1.getBucketQuota().getMaxSizeKb() * 1024)) * 100);
    }

    public List<SubUser> createSubUser(String uid, SSubUser subUser) {
        RgwAdmin rgwAdmin = getRgwAdmin();
        Map<String, String> subUserParam = new HashMap<>();
        subUserParam.put("access-key", subUser.getAccessKey());
        subUserParam.put("secret-key", subUser.getSecretKey());
        subUserParam.put("key-type", "s3");
        subUserParam.put("access", SubUser.Permission.NONE.toString());
        return rgwAdmin.createSubUser(uid, subUser.getSubUid(), subUserParam);
    }

    public String subUserInfo(String uid, String subUid) {
        RgwAdmin rgwAdmin = getRgwAdmin();
        Optional<SubUser> optionalSubUser = rgwAdmin.getSubUserInfo(uid, subUid);

        SubUser subUser = optionalSubUser.get();

        return subUser.getPermission().toString();
    }

    public void setSubUserPermission(String uid, String subUid, String permission) {
        RgwAdmin rgwAdmin = getRgwAdmin();

        rgwAdmin.setSubUserPermission(uid, subUid, SubUser.Permission.valueOf(permission.toUpperCase()));

    }

    public void deleteSubUser(String uid, String subUid, Key key) {
        RgwAdmin rgwAdmin = getRgwAdmin();
        rgwAdmin.removeS3CredentialFromSubUser(uid, subUid, key.getAccessKey());
        rgwAdmin.removeSubUser(uid, subUid);
    }

    public void alterSubUserKey(String uid, String subUid, Key key) {
        RgwAdmin rgwAdmin = getRgwAdmin();
        rgwAdmin.createS3CredentialForSubUser(uid, subUid, key.getAccessKey(), key.getSecretKey());
    }

    // nodejs 코드에서 입력 파라미터로 uid만을 받게 설계돼 있어서 우리도 key 빼야할지 고민해봐야함.
    public void createS3Credential(String uid, Key key){
        RgwAdmin rgwAdmin = getRgwAdmin();

        rgwAdmin.createS3Credential(uid, key.getAccessKey(), key.getSecretKey());
    }

    public void createS3Credential(String uid){
        RgwAdmin rgwAdmin = getRgwAdmin();

        rgwAdmin.createS3Credential(uid);
    }

    public void deleteS3Credential(String uid, String accessKey){
        RgwAdmin rgwAdmin = getRgwAdmin();
        rgwAdmin.removeS3Credential(uid, accessKey);
    }

    public List<S3Credential> getS3Credential(String uid){
        RgwAdmin rgwAdmin = getRgwAdmin();
        Optional<User> userInfo = rgwAdmin.getUserInfo(uid);

        return userInfo.map(User::getS3Credentials).orElse(null);
    }

    public User createUser(SUser user) {
        RgwAdmin rgwAdmin = getRgwAdmin();
        Map<String, String> userParameters = new HashMap<>();
        userParameters.put("display-name", user.getDisplayName());
        userParameters.put("email", user.getEmail());
        return rgwAdmin.createUser(user.getUid(), userParameters);
    }

    public void addBucketUser(Key key, String rgwuser, String permission, String bucketName) {
        AmazonS3 conn = getClient(key);

        AccessControlList accessControlList = conn.getBucketAcl(bucketName);
        Grant grant = new Grant(new CanonicalGrantee(rgwuser), Permission.valueOf(permission));

        accessControlList.grantAllPermissions(grant);
        conn.setBucketAcl(bucketName, accessControlList);

        List<BObject> objectList = getObjects(key, bucketName);

        addObjectPermission(conn, objectList, grant.getPermission(), bucketName);
    }

    public void addObjectPermission(AmazonS3 conn, List<BObject> objectList, Permission permission, String bucketName){
        for(BObject bObject : objectList){
            Grant grant = new Grant(new CanonicalGrantee(conn.getS3AccountOwner().getId()), permission);
            AccessControlList accessControlList = conn.getObjectAcl(bucketName, bObject.getObjectName());
            accessControlList.grantAllPermissions(grant);
            conn.setObjectAcl(bucketName, bObject.getObjectName(), accessControlList);
        }
    }





    public void bucketAclTest() {
        AmazonS3 conn = getClient(new Key("MB9VKP4AC9TZPV1UDEO4" , "UYScnoXxLtmAemx4gAPjByZmbDnaYuOPOdpG7vMw"));
//        AccessControlList accessControlList = conn.getBucketAcl("foo-test-bucket2");
        // 기존 Grant를 가져올 Canonical ID 또는 AWS 계정 ID
//        String existingCanonicalId = "foo_user2";
//
//        AccessControlList accessControlList = conn.getBucketAcl("foo-test-bucket2");
//        Grant grant4 = new Grant(new CanonicalGrantee("foo_user2"), Permission.FullControl);
//
//        accessControlList.grantAllPermissions(grant4);
//        conn.setBucketAcl("foo-test-bucket2", accessControlList);
//
//        List<BObject> objectList = getObjects(new Key("MB9VKP4AC9TZPV1UDEO4" , "UYScnoXxLtmAemx4gAPjByZmbDnaYuOPOdpG7vMw"), "foo-test-bucket2");
//
//        System.out.println(conn.getBucketAcl("foo-test-bucket2"));
//
//
//        for(BObject bObject : objectList){
//            AccessControlList accessControlList12 = conn.getObjectAcl("foo-test-bucket2", bObject.getObjectName());
//            Grant grant5 = new Grant(new CanonicalGrantee("foo_user2"), Permission.FullControl);
//
//            accessControlList12.grantAllPermissions(grant5);
//
//            conn.setObjectAcl("foo-test-bucket2",bObject.getObjectName(), accessControlList12);
//        }
        conn.setBucketAcl("foo-test-bucket2", CannedAccessControlList.PublicRead);
    }
}