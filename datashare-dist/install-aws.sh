#!/bin/bash

# Install JRE8
wget --no-cookies --no-check-certificate --header "Cookie: gpw_e24=http%3A%2F%2Fwww.oracle.com%2F; oraclelicense=accept-securebackup-cookie" "http://download.oracle.com/otn-pub/java/jdk/8u112-b15/jre-8u112-linux-x64.tar.gz"
tar xvzf jre-8u112-linux-x64.tar.gz
sudo mkdir /usr/lib/jvm/
sudo mv jre1.8.0_112 /usr/lib/jvm/
sudo update-alternatives --install "/usr/bin/java" "java" "/usr/lib/jvm/jre1.8.0_112/bin/java" 1

# Elasticsearch 
sysctl -w vm.max_map_count=262144

