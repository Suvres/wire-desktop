FROM node:lts

WORKDIR /app

CMD apt-get update; apt-get upgrade -y; apt-get install -y rpm; \
    export LINUX_TARGET=deb; \
    yarn; \
    yarn build:linux;



