package com.stm.callcontrol;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/** Read-only access to the WORM bucket: stream-hash an object and pre-sign short-lived download URLs.
 *  Nothing here writes or deletes; Egress writes, and Object Lock prevents anyone deleting. */
@Component
public class ObjectStore {
  public record Digest(String sha256, long size) {}
  private final S3Client s3;
  private final S3Presigner presigner;
  private final String bucket;

  public ObjectStore(@Value("${storage.endpoint}") String endpoint, @Value("${storage.public-endpoint}") String publicEndpoint,
                     @Value("${storage.region}") String region, @Value("${storage.access-key}") String ak,
                     @Value("${storage.secret-key}") String sk, @Value("${storage.bucket}") String bucket) {
    var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk));
    var cfg = S3Configuration.builder().pathStyleAccessEnabled(true).build();
    this.bucket = bucket;
    this.s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region))
        .credentialsProvider(creds).serviceConfiguration(cfg).build();
    this.presigner = S3Presigner.builder().endpointOverride(URI.create(publicEndpoint)).region(Region.of(region))
        .credentialsProvider(creds).serviceConfiguration(cfg).build();
  }

  public Digest sha256(String key) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    long n = 0;
    try (var in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
      byte[] buf = new byte[1 << 16];
      int r;
      while ((r = in.read(buf)) != -1) { md.update(buf, 0, r); n += r; }
    }
    return new Digest(HexFormat.of().formatHex(md.digest()), n);
  }

  public String presign(String key, Duration ttl) {
    var req = GetObjectPresignRequest.builder().signatureDuration(ttl)
        .getObjectRequest(b -> b.bucket(bucket).key(key)).build();
    return presigner.presignGetObject(req).url().toString();
  }
}
