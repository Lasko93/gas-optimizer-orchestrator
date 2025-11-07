FROM node:20-alpine

RUN apk add --no-cache bash && npm i -g truffle@5

WORKDIR /workspace

ENV CONTRACTS_DIR=/workspace/truffle/contracts \
    BUILD_DIR=/workspace/truffle/build/contracts \
    SOLC_VERSION=0.8.24

COPY truffle-entrypoint.sh /usr/local/bin/truffle-entrypoint.sh
RUN chmod +x /usr/local/bin/truffle-entrypoint.sh

ENTRYPOINT ["truffle-entrypoint.sh"]
