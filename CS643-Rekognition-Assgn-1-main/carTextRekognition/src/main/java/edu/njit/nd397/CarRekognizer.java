package edu.njit.nd397;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;

// AWS Credential helpers
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

// Rekognition imports
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.Label;
// S3 Imports
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

public class CarRekognizer {
  // Clients
  S3Client s3Client;
  RekognitionClient rekognitionClient;
  SqsClient sqsClient;

  // Variables
  String bucketName;
  String labelToBeRekognized;
  Float minimumConfidenceRequired;

  String queueGroup = "queue-group";
  String queueName = "car-images-queue.fifo";
  String queueUrl = ""; // We will create it on the fly if it doesnt exist

  private static Properties loadApplicationProperties() {
    String rootPath = Thread.currentThread().getContextClassLoader().getResource("").getPath();
    String appConfigPath = rootPath + "app.properties";

    Properties appProps = new Properties();
    try {
      appProps.load(new FileInputStream(appConfigPath));
    } catch (FileNotFoundException e) {
      // TODO: handle exception
      System.out.println(e.getMessage());
      System.out.println("app.properties File not found at " + appConfigPath);
      System.exit(1);
    } catch (IOException e) {
      // TODO: handle exception
      System.out.println(e.getMessage());
      System.out.println("cannot read app.properties!!");
      System.exit(1);
    }

    return appProps;
  }

  public static void main(String[] args) {
    System.out.println("Running the car rekognition application!!");

    // Get the application properties
    Properties appProps = loadApplicationProperties();

    String aws_access_key_id = appProps.getProperty("AWS_ACCESS_KEY_ID");
    String aws_secret_access_key = appProps.getProperty("AWS_SECRET_ACCESS_KEY");

    if (aws_access_key_id == null || aws_secret_access_key == null) {
      System.out.print("AWS_ACCESS_KEY_ID or AWS_SECRET_ACCESS_KEY cannot be empty in the app.properties");
      System.exit(1);
    }

    Region clientRegion = Region.US_EAST_1;
    String bucketName = "njit-cs-643";
    String labelToBeRekognized = "car";
    Float minimumConfidenceRequired = 90.00f;

    AwsBasicCredentials credentials = AwsBasicCredentials.create(aws_access_key_id,
        aws_secret_access_key);

    StaticCredentialsProvider staticCredentials = StaticCredentialsProvider.create(credentials);

    // Build the S3 Client
    S3Client s3Client = S3Client.builder()
        .region(clientRegion)
        .credentialsProvider(staticCredentials)
        .build();

    // Build the Rekognition Client
    RekognitionClient rekognitionClient = RekognitionClient.builder()
        .region(clientRegion)
        .credentialsProvider(staticCredentials)
        .build();

    // Build the SQS client
    SqsClient sqsClient = SqsClient.builder()
        .region(clientRegion)
        .credentialsProvider(staticCredentials)
        .build();

    CarRekognizer instance = new CarRekognizer(s3Client, rekognitionClient,
        sqsClient, bucketName, labelToBeRekognized,
        minimumConfidenceRequired);

    instance.doYourThing();
  }

  // Constructor
  public CarRekognizer(S3Client s3Client, RekognitionClient rekognitionClient, SqsClient sqsClient, String bucketName,
      String labelName, Float minimumConfidenceRequired) {
    this.s3Client = s3Client;
    this.rekognitionClient = rekognitionClient;
    this.sqsClient = sqsClient;

    this.bucketName = bucketName;
    this.labelToBeRekognized = labelName;
    this.minimumConfidenceRequired = minimumConfidenceRequired;
  }

  private String createQueueIfNotExists() {
    String queueUrl = "";

    try {
      ListQueuesRequest listQueuesRequest = ListQueuesRequest.builder()
          .queueNamePrefix(queueName)
          .build();

      ListQueuesResponse listQueuesResponse = this.sqsClient.listQueues(listQueuesRequest);

      if (listQueuesResponse.queueUrls().size() == 0) {
        // No Queue Exists, So create one!
        System.out.println("Queue doesnt exists! Creating a new one!!");

        CreateQueueRequest createQueueRequest = CreateQueueRequest.builder()
            .attributesWithStrings(Map.of("FifoQueue", "true",
                "ContentBasedDeduplication", "true"))
            .queueName(queueName)
            .build();
        sqsClient.createQueue(createQueueRequest);

        GetQueueUrlRequest getQueueUrlRequest = GetQueueUrlRequest.builder()
            .queueName(queueName)
            .build();

        queueUrl = sqsClient.getQueueUrl(getQueueUrlRequest).queueUrl();

      } else {
        // Queue already exists, So use it
        System.out.println("Queue already exists! Won't create a new one!!");
        queueUrl = listQueuesResponse.queueUrls().get(0);
      }

      System.out.println("Queue URL: " + queueUrl);

      return queueUrl;
    } catch (SqsException e) {
      // TODO: handle exception
      System.err.println(e.awsErrorDetails().errorMessage());
      System.exit(1);
    }

    // runtime will never reach here!! due to system.exit()
    return queueUrl;
  }

  private void sendImageToSQS(String imageKey) {
    // Check if queueURL exists, else create one
    if (this.queueUrl.length() == 0) {
      // Queue url hasnt been initialized, Initialize!!
      this.queueUrl = createQueueIfNotExists();
    }

    this.sqsClient.sendMessage(SendMessageRequest.builder().messageGroupId(this.queueGroup).queueUrl(this.queueUrl)
        .messageBody(imageKey).build());
  }

  private List<String> getImagesFromBucket() {
    System.out.println("Get the images from S3");
    List<String> s3ObjKeys = new ArrayList<>();

    try {
      ListObjectsRequest listObjects = ListObjectsRequest
          .builder()
          .bucket(this.bucketName)
          .build();
      ListObjectsResponse res = s3Client.listObjects(listObjects);
      List<S3Object> objects = res.contents();

      for (ListIterator<S3Object> iterVals = objects.listIterator(); iterVals.hasNext();) {
        S3Object s3Object = (S3Object) iterVals.next();
        System.out.println("Image found in njit-cs-643 S3 bucket: " + s3Object.key());

        s3ObjKeys.add(s3Object.key());
      }

      System.out.println("---Got all the images from S3----");

    } catch (S3Exception e) {
      // TODO: handle exception
      System.err.println(e.awsErrorDetails().errorMessage());
      System.exit(1);
    }

    return s3ObjKeys;
  }

  private void rekognizeImageAndAddToQueue(String labelToBeRekognized, Float minimumConfidenceRequired,
      List<String> imagesKeyList) {
    System.out.println(
        "Starting to rekognize images and adding to queue if label \"" + labelToBeRekognized + "\" is found with "
            + minimumConfidenceRequired + " !!!");

    for (String imageKey : imagesKeyList) {
      // Recognize the image using aws rekognition
      Image img = Image.builder().s3Object(software.amazon.awssdk.services.rekognition.model.S3Object
          .builder().bucket(this.bucketName).name(imageKey).build())
          .build();

      DetectLabelsRequest detectLabelRequest = DetectLabelsRequest.builder().image(img)
          .minConfidence(minimumConfidenceRequired)
          .build();

      DetectLabelsResponse detectionResult = this.rekognitionClient.detectLabels(detectLabelRequest);
      List<Label> labels = detectionResult.labels();

      for (Label label : labels) {

        if (label.name().equals("Car")) {
          System.out.println("----Sending image " + imageKey + " for text rekognition!!---");

          // Send the image for further processing!!
          sendImageToSQS(imageKey);

          // Dont wanna check anymore labels for this image, so break!!!
          break;
        }
      }
    }
  }

  private void doYourThing() {
    // Get the image list
    List<String> imagesKeyList = getImagesFromBucket();

    // Rekognize the images
    rekognizeImageAndAddToQueue(this.labelToBeRekognized,
        this.minimumConfidenceRequired, imagesKeyList);

    // Close the clients
    this.s3Client.close();
    this.rekognitionClient.close();
    this.sqsClient.close();
    System.exit(0);
  }
}
