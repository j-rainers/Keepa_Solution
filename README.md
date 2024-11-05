# Keepa Solution Test App

This document provides installation and setup instructions for running the application on a Linux system, as well as scheduling it to run automatically using `cron`.

## Prerequisites

Needed software:

1. **Java Development Kit (JDK)** - Required to compile and run the Java application.
   - Install via your package manager, e.g.:
     ```bash
     sudo apt update
     sudo apt install openjdk-11-jdk
     ```
   - Verify the installation:
     ```bash
     java -version
     ```

2. **Apache Maven** - Used for building and managing the project.
   - Install Maven via your package manager, e.g.:
     ```bash
     sudo apt update
     sudo apt install maven
     ```
   - Verify the installation:
     ```bash
     mvn -version
     ```

## Project Setup

### 1. Clone the Repository:

First, clone the project repository to your local machine:

```bash
git clone https://github.com/RainersJurkste/Keepa_Solution.git
```

### 2. Go to project directory:
```bash
cd ~/Keepa_Solution/test-app
```

### 3. Create .env file

`.env` file should be under:
```bash
~/Keepa_Solution/test-app
```

The `.env` file should look like this:
```
API_KEY=
DB_URL=
DB_USER=
DB_SCHEMA=
DB_PASSWORD=
```

### 4. Create "run_app.sh" script:

Needed for automating the process:
```bash
#!/bin/bash

cd ~/Keepa_Solution/test-app || { echo "Failed to change directory to ~/Keepa_Solution/test-app"; exit 1; }

# Check if the pom.xml exists
if [ ! -f pom.xml ]; then
    echo "pom.xml not found in $(pwd)"
    exit 1
fi

# Kill any existing Java process related to the application
pkill -f 'org.codehaus.plexus.classworlds.launcher.Launcher'

# Build the project using Maven
/usr/bin/mvn clean install

# Run the application using Maven
/usr/bin/mvn exec:java -Dexec.mainClass="test.App"
```

Make the script executable:
```bash
chmod +x run_app.sh
```

### 5. Running the application manually

To manually run the application, execute the following command from the test-app directory:
```bash
./run_app.sh
```

This will:\
    1. Navigate to the project directory.\
    2. Check for the presence of the pom.xml file.\
    3. Build the project using Maven.\
    4. Run the Java application, specifying the main class as test.App.\

## Scheduling the app with cron

### 1. Open crontab
```bash
crontab -e
```

### 2. Add cronjob
```
* * * * * Keepa_Solution/test-app/run_app.sh >> Keepa_Solution/test-app/cronjob.log 2>&1
* * * * * pkill -f 'org.codehaus.plexus.classworlds.launcher.Launcher'

```

### 3. Verify cronjob
```bash
crontab -l
```

## Troubleshooting
1. Ensure that run_app.sh has execute permissions:
```bash
chmod +x ~/Keepa_Solution/test-app/run_app.sh
```

2. If Maven or Java commands fail, ensure that they are installed correctly and accessible via `/usr/bin`.

3. Check your cron logs for any errors if the scheduled task does not run as expected:
```bash
grep CRON /var/log/syslog
```
