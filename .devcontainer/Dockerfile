ARG user_uid=1000
ARG user_gid=1000
ARG docker_gid=999
ARG arch=amd64

FROM phusion/baseimage:jammy-1.0.1

ARG user_uid
ARG user_gid
ARG docker_gid
ARG arch

RUN add-apt-repository --yes ppa:deadsnakes/ppa

RUN apt-get -y update && apt-get -y install tzdata sudo lxc python3 python3-apt wget
ENV NOTVISIBLE "in users profile"
RUN echo "export VISIBLE=now" >> /etc/profile

# locale UTF-8 fr
RUN apt-get -y install language-pack-fr && /usr/sbin/update-locale
RUN echo 'fr_FR.UTF-8 UTF-8' > /etc/locale.gen && /usr/sbin/locale-gen

# timezone Europe/Paris
RUN echo "tzdata tzdata/Areas select Europe" > /tmp/tzdata.txt && echo "tzdata tzdata/Zones/Europe select Paris" >> /tmp/tzdata.txt && debconf-set-selections /tmp/tzdata.txt && rm /etc/timezone && rm /etc/localtime && dpkg-reconfigure -f noninteractive tzdata

# with your user / group id
RUN export uid=$user_uid gid=$user_gid internal_user=dev && \
    mkdir -p /home/${internal_user} && \
    echo "${internal_user}:x:${uid}:${gid}:${internal_user},,,:/home/${internal_user}:/bin/bash" >> /etc/passwd && \
    echo "${internal_user}:*:18750:0:99999:7:::" >> /etc/shadow && \
    echo "${internal_user}:x:${uid}:" >> /etc/group && \
    echo "${internal_user} ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/${internal_user} && \
    chmod 0440 /etc/sudoers.d/${internal_user} && \
    chown ${uid}:${gid} -R /home/${internal_user} && \
    devenv_docker_groupname=$(getent group "$docker_gid" | cut -d ':' -f 1) && \
    if [ -z "$devenv_docker_groupname" ] ; then groupadd -g $docker_gid docker; devenv_docker_groupname=docker; fi && \
    usermod -aG $devenv_docker_groupname dev

# postgresql 10 client
RUN wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add -
RUN echo "deb http://apt.postgresql.org/pub/repos/apt/ `lsb_release -cs`-pgdg main" >> /etc/apt/sources.list.d/pgdg.list

# dev utils
RUN apt-get -y update && \
    apt-get -y install \
        # Git
        git gitk git-extras \
        # Shell utils
        zsh man-db bash-completion autoconf tmux \
        xclip ccze xvfb inotify-tools source-highlight strace graphviz \
        # Network
        curl wget tcpdump traceroute ngrep sysstat iputils-ping net-tools \
        maven htop jq tree vim nano autojump pass poppler-utils \
        # Image manipulation
        gnuplot ghostscript imagemagick \
        # Python
        python3-dev python3-pip pinentry-gtk2 \
        # Tools
        build-essential gnupg2 nsis cpio icnsutils \
        software-properties-common qpdf cmake unzip exiftool \
        # Database clients
        mysql-client postgresql-client libpq-dev redis-tools \
        # Libraries
        libasound2 libatk-bridge2.0-0 libatk1.0-0 libcairo2-dev \
        libcups2 libffi-dev libfreetype6-dev libgif-dev libgtk-3-0 libjpeg-dev libappindicator3-1 \
        libjpeg8-dev libmariadb-dev libnss3 libpng-dev pkg-config \
        libpoppler-cpp-dev libpoppler-dev \
        libssl-dev  libxcomposite1 libxcursor1 libxi6 libxml2-dev \
        libxrandr2 libxslt1-dev libxss1 libxtst6 zlib1g-dev \
        libldap2-dev libsasl2-dev slapd ldap-utils tox \
        lcov valgrind \
        # java & maven
        openjdk-17-jdk \
        maven \
        tesseract-ocr \
        # PHP and PHP extensions
        php php-cli php-curl php-mysql php-xml php-mbstring

# install jbang
RUN curl -Ls https://sh.jbang.dev | bash -s - app setup

# Ensure we use the latest version of pip
RUN python3 -m pip install --upgrade pip && \
    pip3 install boto3 virtualenv cerberus passpy python-ldap sh pre-commit

# Install a specific version of SOPS
RUN wget https://github.com/mozilla/sops/releases/download/v3.7.3/sops_3.7.3_$arch.deb -O /tmp/sops.deb && \
    dpkg -i /tmp/sops.deb && \
    apt install -f && \
    rm -f /tmp/sops.deb

ENV HOME="/home/dev"
ENV LANGUAGE="en"
ENV LANG="en_US.UTF-8"
ENV TERM xterm-256color

# Install pipx
RUN python3 -m pip install pipx virtualenv && \
    python3 -m pipx ensurepath && \
    apt update -y && apt install -y python3.10-venv

ENV PIPX_HOME /opt/pipx/home
ENV PIPX_BIN_DIR /opt/pipx/bin

RUN install -d -o dev -g dev -m 0700 $PIPX_HOME
RUN install -d -o dev -g dev -m 0700 $PIPX_BIN_DIR

ENV PATH="$PATH:$PIPX_BIN_DIR"

# Set Java 17 as default
RUN update-alternatives --set java /usr/lib/jvm/java-17-openjdk-amd64/bin/java && \
    update-alternatives --set javac /usr/lib/jvm/java-17-openjdk-amd64/bin/javac

# Set JAVA_HOME environment variable
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

#n node version manager
RUN mkdir -p /opt/n && chown dev /opt/n && \
    curl -L https://git.io/n-install | PREFIX=/opt/n N_PREFIX=/opt/n bash -s -- -y

# npm update global packages
RUN /opt/n/bin/n 20 && node --dns-result-order=ipv4first /usr/local/bin/npm install --location=global yarn@1.22.19

# configure shell before RVM
SHELL ["/bin/bash", "-l", "-c"]

# We use the dev user by default but it's important the entrypoint
# run the /sbin/my_init script the the container is running properly
# @see https://github.com/phusion/baseimage-docker#whats-inside-the-image
USER dev

# Install pip binaries with pipx
RUN python3 -m pipx install ansible==8.0.0 --include-deps --force
RUN python3 -m pipx inject ansible boto3 botocore python-ldap
RUN python3 -m pipx install cruft==2.11.1 --include-deps
RUN python3 -m pipx install cookiecutter==1.7.3 --include-deps
RUN python3 -m pipx install pipenv --include-deps
RUN python3 -m pipx install awscli --include-deps
RUN python3 -m pipx install poetry==1.3.1 --include-deps

WORKDIR /home/dev
