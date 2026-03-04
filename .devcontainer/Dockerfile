FROM phusion/baseimage:jammy-1.0.1

ARG BUILDPLATFORM

RUN add-apt-repository --yes ppa:deadsnakes/ppa

RUN apt-get -y update && apt-get -y install tzdata sudo lxc wget

# postgresql 10 client
RUN wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add -
RUN echo "deb http://apt.postgresql.org/pub/repos/apt/ `lsb_release -cs`-pgdg main" >> /etc/apt/sources.list.d/pgdg.list

# dev utils
RUN apt-get -y update && \
    apt-get -y install \
        # Git
        git gitk git-extras \
        # Shell utils
        zsh man-db bash-completion autoconf ccze \
        # Network
        curl jq \
        # Image manipulation
        ghostscript imagemagick \
        # Tools
        build-essential software-properties-common unzip \
        # Database clients
        mysql-client postgresql-client libpq-dev redis-tools \
        # Libraries
         libgif-dev libgtk-3-0 libjpeg-dev libappindicator3-1 \
        libjpeg8-dev libpng-dev pkg-config \
        libssl-dev libxml2-dev \
        # java & maven
        openjdk-21-jdk \
        maven \
        tesseract-ocr 

ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000

# Create the user + group 
RUN groupadd --gid ${USER_GID} ${USERNAME} \
  && useradd --uid ${USER_UID} --gid ${USER_GID} -m -s /bin/bash ${USERNAME} \
  && echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" >/etc/sudoers.d/${USERNAME} \
  && chmod 0440 /etc/sudoers.d/${USERNAME}

ENV HOME="/home/dev"
ENV LANGUAGE="en"
ENV LANG="en_US.UTF-8"
ENV TERM xterm-256color
ENV JDK_ARCH=${BUILDPLATFORM#*/}


RUN ln -s /usr/lib/jvm/java-21-openjdk-$JDK_ARCH /usr/lib/jvm/java-21-openjdk

# Set JAVA_HOME environment variable
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /home/dev/workspace/datashare

RUN git config --global --add safe.directory "/home/dev/workspace/datashare"


FROM temporalio/admin-tools:1.29 AS temporal-admin-tools

FROM temporalio/server:1.29.3 AS temporal-test-container
COPY --chmod=755 --from=temporal-admin-tools /usr/local/bin/temporal-sql-tool /usr/local/bin/
COPY --from=temporal-admin-tools /etc/temporal/schema /etc/temporal/schema
COPY --chmod=755 ./temporal/dynamic.yml ./temporal/temporal.yml ./temporal/setup.sh ./temporal/entrypoint-override.sh /etc/temporal/.config/temporalio/
ENV POSTGRES_STARTUP_TIMEOUT=15
ENTRYPOINT [ "/etc/temporal/.config/temporalio/entrypoint-override.sh"]
CMD [ "/etc/temporal/.config/temporalio/entrypoint-override.sh"]
