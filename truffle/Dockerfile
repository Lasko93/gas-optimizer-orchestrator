FROM node:20-alpine

RUN apk add --no-cache bash git && npm i -g truffle@5

WORKDIR /workspace

COPY truffle-entrypoint.sh /usr/local/bin/truffle-entrypoint.sh
RUN chmod +x /usr/local/bin/truffle-entrypoint.sh

ENTRYPOINT ["truffle-entrypoint.sh"]
