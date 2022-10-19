## Introduction
We will be setting up two instances on the aws for processing car images to get the text out of it.


## Intial steps for AWS Credentials setup
We will be setting up the cloud **environment** and spinning two EC2 instaces. We will be doing this all using 12 months free tier plan.

1. Create an AWS Account with NJIT email.
2. Login to the AWS Account and find IAM(For setting up he credentials for the application)
  
    Goto "IAM" -> "Access Management" -> "Policies" -> "Create policy"(eg: module03Assignment3). Choose the following services with full access:

        Rekognition
        S3
        SQS

    This will help us create an access key, which would be only allowed to do the above mentioned actions.

3. Create AWS Credentials (Access and Secret Keys)
   
	  a. Search for "IAM" in the search bar, and click on it.

    b. Click on "Access Management" on the left bar and then click on "Users"

    d. Create a new user using "Add User" button, (e.g. module03Assignment3)

    e. Type in the username and check on "Access key - Programmatic access" and click "next"

    f. Select "Attach existing policies directly" and choose the policy we created in our last step

    g. After creating user, in the last step there would be an option to download the credentials in `.csv` format, Please keep it handy.


## Steps to setup EC2 instances on the AWS

1. Search for "EC2" on the search bar, and click on the first option
2. Click on the "Instances" in the left side navigation bar
3. Click on "Launch Instances"
4. Select the "Amazon linux 2 AMI - free tier eligible"
5. Click "Next: Configure Security Group"
   
    Click "Add Rule" to include the following and it will autofill:
    SSH, HTTP, HTTPS
    Under 'Source' drop down for each rule, select 'My IP'

6. setup "key pair" for login
    Create the "Key pair", once you create, it will download the key file. Save it carefully.
     
8. Click "Launch instance" to launch the instance.

### Connecting to the EC2 instance using ssh

Use the following command to fix the permission of the .pem file:
	$ chmod 400 <key name>.pem

Use the following command to connect to the created instance (replace <YOUR_EC2_INSTANCE_PUBLIC_DNS> with the "Public IPv4 DNS" property of either EC2 instance):
	$ ssh -i "~/<key name>.pem" ec2-user@<YOUR_EC2_INSTANCE_PUBLIC_IPV4_ADDRESS>

  After connecting to the instance, install java,maven and git as its required by our project using following commands
  ```sh
    sudo yum update
    sudo yum install java-1.8.0-openjdk git
    sudo amazon-linux-extras install java-openjdk11 -y
  ```

  Install maven using below commands
  
  ```sh
  sudo wget https://downloads.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz -O /tmp/apache-maven-3.8.6-bin.tar.gz
  sudo tar xf /tmp/apache-maven-3.8.6-bin.tar.gz -C /opt
  sudo ln -s /opt/apache-maven-3.8.6 /opt/maven
  ```

  add the following lines in `maven.sh` for it to work properly
  
  ```sh
  sudo nano /etc/profile.d/maven.sh
  ```
  ```sh
  export JAVA_HOME=/usr/lib/jvm/jre-11-openjdk
  export M2_HOME=/opt/maven
  export MAVEN_HOME=/opt/maven
  export PATH=${M2_HOME}/bin:${PATH}
  ```
  ```sh
  sudo chmod +x /etc/profile.d/maven.sh
  source /etc/profile.d/maven.sh
  mvn --version
  ```
	
### Running the application on EC2 instance

#### Car Recognizer
Log into the ec2-a and clone the repo using the following command

	$ git clone https://github.com/NaveenkumarreddyD/CS643-Rekognition-Assgn-1

**Copy the previously downloaded creditials into `app.properties` file.**

use the following commands to run the carImageRecognition part of the project

```sh
cd CS643-Rekognition-Assgn-1/carTextRekognition/
mvn install
cp ../app.properties ./target/classes/
mvn exec:java@carRekognizer
```

#### Text Extractor

Log into the ec2-b and clone the repo using the following command

	$ git clone https://github.com/NaveenkumarreddyD/CS643-Rekognition-Assgn-1

**Copy the previously downloaded creditials into `app.properties` file.**

use the following commands to run the carImageRecognition part of the project

```sh
cd CS643-Rekognition-Assgn-1/carTextRekognition/
mvn install
cp ../app.properties ./target/classes/
mvn exec:java@textRekognizer
```