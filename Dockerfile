# TikTok AutoPoster Android - Build Dockerfile (ARM64 compatible)
# Usage: docker build -t autoposter-android . && docker run -v $(pwd)/output:/output autoposter-android

# Use ARM-native Eclipse Temurin JDK 17
FROM eclipse-temurin:17-jdk

# Environment variables
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH="${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools"

# Install required packages
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Install Android SDK Command Line Tools
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    cd ${ANDROID_SDK_ROOT}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip && \
    unzip -q cmdline-tools.zip && \
    mv cmdline-tools latest && \
    rm cmdline-tools.zip

# Accept licenses and install required SDK components
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true && \
    sdkmanager "platforms;android-34" "build-tools;34.0.0"

WORKDIR /app

# Copy project files
COPY . .

# Make gradlew executable
RUN chmod +x gradlew

# Create output directory
RUN mkdir -p /output

# Build command
CMD ["sh", "-c", "./gradlew assembleDebug --no-daemon && cp app/build/outputs/apk/debug/*.apk /output/ && echo 'APK built successfully!'"]
