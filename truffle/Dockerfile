# truffle/Dockerfile
FROM node:20-alpine

# Useful build tools + git + netcat for RPC wait
RUN apk add --no-cache bash git python3 make g++ netcat-openbsd curl jq

# Truffle CLI (+ hdwallet provider if you ever need it)
RUN npm i -g truffle@5 @truffle/hdwallet-provider

# Entrypoint script
COPY truffle-entrypoint.sh /usr/local/bin/truffle-entrypoint
RUN chmod +x /usr/local/bin/truffle-entrypoint

WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/truffle-entrypoint"]
