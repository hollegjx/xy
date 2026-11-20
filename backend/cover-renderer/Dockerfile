FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends chromium fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /renderer
RUN npm install --no-audit --no-fund --ignore-scripts playwright-core@1.56.1
COPY render.cjs .
ENV CHROMIUM_PATH=/usr/bin/chromium HOME=/tmp
USER node
ENTRYPOINT ["timeout", "-k", "1", "12", "node", "/renderer/render.cjs"]
