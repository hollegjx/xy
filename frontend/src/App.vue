<script setup lang="ts">
import { ConfigProvider as AConfigProvider } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { RouterView } from 'vue-router'
</script>

<template>
  <AConfigProvider
    :locale="zhCN"
    :theme="{ token: { colorPrimary: '#356859', borderRadius: 10, colorText: '#24362e' } }"
  >
    <RouterView />
  </AConfigProvider>
</template>
