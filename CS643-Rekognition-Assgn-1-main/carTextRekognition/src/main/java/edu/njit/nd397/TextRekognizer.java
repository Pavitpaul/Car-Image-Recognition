package edu.njit.pm66;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectTextRequest;
import software.amazon.awssdk.services.rekognition.model.DetectTextResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.S3Object;
import software.amazon.awssdk.services.rekognition.model.TextDetection;
import software.amazon.awssdk.services.rekognition.model.TextTypes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesRequest;
import software.amazon.awssdk.services.sqs.model.ListQueuesResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueNameExistsException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

public class TextRekognizer {
  // Clients
  S3Client s3Client;
  RekognitionClient rekognitionClient;
  SqsClient sqsClient;

  // Variables
  String bucketName;
  Boolean processingInProgress = false;

  String queueGroup = "queue-group";
  String queueName = "car-images-queue.fifo";
  String queueUrl = ""; // We will create it on the fly if it doesnt exist
  long sleepTime = 10 * 1000;

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
    System.out.println("Running the text rekognition application!!");

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

    TextRekognizer instance = new TextRekognizer(s3Client, rekognitionClient,
        sqsClient, bucketName);

    instance.doYourThing();
  }

  // Constructor
  public TextRekognizer(S3Client s3Client, RekognitionClient rekognitionClient, SqsClient sqsClient,
      String bucketName) {
    this.s3Client = s3Client;
    this.rekognitionClient = rekognitionClient;
    this.sqsClient = sqsClient;

    this.bucketName = bucketName;
  }

  private String getQueurUrl() {
    // Poll SQS until the queue is created (by DetectCars)
    boolean queueExists = false;

    String queueUrl = "";

    while (!queueExists) {
      ListQueuesRequest listQueueRequest = ListQueuesRequest.builder()
          .queueNamePrefix(queueName)
          .build();

      ListQueuesResponse listQueueResponse = this.sqsClient.listQueues(listQueueRequest);

      if (listQueueResponse.queueUrls().size() > 0) {
        queueExists = true;
        System.out.println("Queue found, Proceeding further!");
      } else {
        // Sleep for some time as we dont want flood with the request!
        try {
          System.out.println("Queue not found, waiting for " + this.sleepTime + "ms!!");
          Thread.sleep(this.sleepTime);
        } catch (InterruptedException e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
      }

    }

    try {
      GetQueueUrlRequest getQueueUrlRequest = GetQueueUrlRequest.builder()
          .queueName(queueName)
          .build();
      queueUrl = this.sqsClient.getQueueUrl(getQueueUrlRequest)
          .queueUrl();

      System.out.println("QueueURL: " + queueUrl);

    } catch (QueueNameExistsException e) {
      // TODO: handle exception
      System.err.println(e.awsErrorDetails().errorMessage());
      System.exit(1);
    }

    return queueUrl;
  }

  private void appendTextToFile(String text) {
    try {
      Files.writeString(
          Path.of(System.getProperty("java.io.tmpdir"), "textOutput.txt"),
          text + System.lineSeparator(),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      // Print using `cat /tmp/textOutput.txt`
    } catch (IOException e) {
      e.printStackTrace();
      System.out.println("Unable to write text to file!!");
    }
  }

  private void processImage(String imageKey) {

    // This lets us stop the application from processing other images before one is
    // done!
    processingInProgress = true;

    Image img = Image.builder().s3Object(S3Object.builder().bucket(this.bucketName).name(imageKey).build())
        .build();
    DetectTextRequest textDetectionRequest = DetectTextRequest.builder()
        .image(img)
        .build();

    DetectTextResponse textDetectionResponse = this.rekognitionClient.detectText(textDetectionRequest);
    List<TextDetection> textDetections = textDetectionResponse.textDetections();
    if (textDetections.size() != 0) {
      String text = "";

      for (TextDetection detectedText : textDetections) {
        if (detectedText.type().equals(TextTypes.WORD))
          text = text.concat(" " + detectedText.detectedText());
      }

      // outputs.put(imageKey, text);
      appendTextToFile(imageKey + ": " + text);
    }

    processingInProgress = false;
  }

  private void checkSqsMessages(int count) {
    System.out.println("Checking for messages! - " + count);

    ReceiveMessageRequest receiveMessageRequest = ReceiveMessageRequest.builder().queueUrl(queueUrl)
        .maxNumberOfMessages(1).build();
    List<Message> messages = this.sqsClient.receiveMessage(receiveMessageRequest).messages();

    if (messages.size() > 0) {
      Message message = messages.get(0);
      String imageKey = message.body();
      System.out.println("Processing car image with text from \"" + this.bucketName + "\" S3 bucket: " + imageKey);

      processImage(imageKey);
      // appendTextToFile(imageKey);

      // Delete the message in the queue now that it's been handled
      DeleteMessageRequest deleteMessageRequest = DeleteMessageRequest.builder().queueUrl(queueUrl)
          .receiptHandle(message.receiptHandle())
          .build();
      this.sqsClient.deleteMessage(deleteMessageRequest);
    }

  }

  private void doYourThing() {
    // Initialize the queue url

    this.queueUrl = getQueurUrl();

    // Get each car and process
    // Start polling the sqs for messages
    int count = 0;
    while (count < 100) {

      if (processingInProgress) {
        System.out.println("Processing image in progress, So skipping this check!");
      } else {
        count++;
        checkSqsMessages(count);
      }

      try {
        TimeUnit.SECONDS.sleep(5);
      } catch (InterruptedException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }

    // Close the clients
    this.s3Client.close();
    this.rekognitionClient.close();
    this.sqsClient.close();
    System.exit(0);
  }

}
