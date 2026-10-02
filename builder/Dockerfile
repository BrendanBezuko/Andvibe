FROM eclipse-temurin:21-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    LANG=C.UTF-8 \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 unzip ca-certificates wget \
    && rm -rf /var/lib/apt/lists/*

# Command-line tools 16.x (September 2026). sdkmanager still installs platforms.
RUN mkdir -p /tmp/cmdline ${ANDROID_HOME}/cmdline-tools/latest \
    && wget -q -O /tmp/cmd.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip \
    && unzip -q /tmp/cmd.zip -d /tmp/cmdline \
    && mv /tmp/cmdline/cmdline-tools/* ${ANDROID_HOME}/cmdline-tools/latest/ \
    && rm -rf /tmp/cmd.zip /tmp/cmdline \
    && mkdir -p /root/.android \
    && touch /root/.android/repositories.cfg

ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN yes | sdkmanager --licenses \
    && sdkmanager --install \
        "platforms;android-37" \
        "platforms;android-36" \
        "build-tools;37.0.0" \
        "build-tools;36.0.0" \
        "platform-tools" \
        "ndk;28.0.13004108"

# Gradle runs as builder and may install more SDK packages, so this tree has to be writable.
RUN useradd --create-home --shell /usr/sbin/nologin builder \
    && mkdir -p /home/builder/work \
    && chown -R builder:builder /home/builder ${ANDROID_HOME}

COPY server.py /opt/server.py

USER builder
WORKDIR /home/builder
ENV HOME=/home/builder \
    GRADLE_USER_HOME=/home/builder/.gradle

EXPOSE 8080
CMD ["python3", "-u", "/opt/server.py"]
