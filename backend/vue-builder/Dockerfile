FROM node:24-alpine
WORKDIR /builder
COPY src/main/resources/templates/vue-project.json template.json
RUN node -e "require('fs').writeFileSync('package.json', JSON.parse(require('fs').readFileSync('template.json', 'utf8'))['package.json'])" \
    && npm install --ignore-scripts --no-audit --no-fund \
    && rm template.json
COPY vue-builder/build.mjs build.mjs
CMD ["node", "/builder/build.mjs"]
