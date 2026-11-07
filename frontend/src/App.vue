<script setup lang="ts">
import { ConfigProvider as AConfigProvider } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { RouterView } from 'vue-router'

const motionEase = 'cubic-bezier(0.22, 1, 0.36, 1)'
</script>

<template>
  <AConfigProvider
    :locale="zhCN"
    :auto-insert-space-in-button="false"
    :theme="{
      token: {
        colorPrimary: '#1d1d1f',
        colorInfo: '#1d1d1f',
        colorText: '#1d1d1f',
        colorTextSecondary: '#6e6e73',
        colorBorder: '#dedee3',
        colorBorderSecondary: '#e8e8ed',
        colorBgLayout: '#ffffff',
        borderRadius: 10,
        borderRadiusSM: 10,
        borderRadiusLG: 16,
        controlHeight: 40,
        fontSize: 14,
        fontFamily:
          '-apple-system, BlinkMacSystemFont, Segoe UI, PingFang SC, Microsoft YaHei, sans-serif',
        motionDurationFast: '240ms',
        motionDurationMid: '240ms',
        motionDurationSlow: '240ms',
        motionEaseOutCirc: motionEase,
        motionEaseInOutCirc: motionEase,
        motionEaseInOut: motionEase,
        motionEaseOutBack: motionEase,
        motionEaseInBack: motionEase,
        motionEaseInQuint: motionEase,
        motionEaseOutQuint: motionEase,
        motionEaseOut: motionEase,
      },
      components: { Button: { controlOutlineWidth: 0 }, Card: { boxShadow: 'none' } },
    }"
  >
    <RouterView />
  </AConfigProvider>
</template>
