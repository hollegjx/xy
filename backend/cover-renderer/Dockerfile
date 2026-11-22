FROM node:22-bookworm-slim AS renderer
RUN apt-get update && apt-get install -y --no-install-recommends chromium fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /renderer
RUN npm install --no-audit --no-fund --ignore-scripts playwright-core@1.56.1
COPY render.cjs .
ENV CHROMIUM_PATH=/usr/bin/chromium HOME=/tmp
USER node
ENTRYPOINT ["timeout", "-k", "1", "12", "node", "/renderer/render.cjs"]

# Compose 的 Dubbo 提供方只向这个 Unix 套接字发送快照，不共享宿主 Docker 或作品目录。
FROM renderer AS socket-renderer
USER root
RUN apt-get update && apt-get install -y --no-install-recommends socat && rm -rf /var/lib/apt/lists/* \
    && install -d -o node -g node -m 0750 /run/xy-cover
USER node
ENTRYPOINT ["socat", "-t", "16", "-T", "16", "UNIX-LISTEN:/run/xy-cover/render.sock,unlink-early,mode=0660,fork,max-children=1", "EXEC:timeout -k 1 12 node /renderer/render.cjs"]

# 既有 xy-cover:1 继续按 stdin/stdout 执行一次，不启动监听器。
FROM renderer AS standalone
