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
        colorPrimary: '#0071e3',
        colorInfo: '#0071e3',
        colorLink: '#0066cc',
        colorSuccess: '#28a745',
        colorWarning: '#ff9f0a',
        colorError: '#ff3b30',
        colorText: '#1d1d1f',
        colorTextSecondary: '#6e6e73',
        colorTextTertiary: '#86868b',
        colorBorder: '#d2d2d7',
        colorBorderSecondary: '#e5e5ea',
        colorBgLayout: '#f5f5f7',
        colorBgContainer: '#ffffff',
        colorFillAlter: '#fafafc',
        controlOutline: 'rgba(0, 113, 227, 0.16)',
        controlOutlineWidth: 3,
        borderRadius: 12,
        borderRadiusSM: 8,
        borderRadiusLG: 18,
        controlHeight: 40,
        controlHeightSM: 30,
        controlHeightLG: 48,
        fontSize: 14,
        fontFamily:
          '-apple-system, BlinkMacSystemFont, SF Pro Text, Helvetica Neue, PingFang SC, Hiragino Sans GB, Microsoft YaHei, Noto Sans CJK SC, sans-serif',
        boxShadowSecondary: '0 2px 8px rgba(0, 0, 0, 0.06), 0 16px 48px rgba(0, 0, 0, 0.12)',
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
      components: {
        Button: { controlOutlineWidth: 0 },
        Card: { boxShadow: 'none' },
        Table: { colorFillAlter: '#fafafc' },
      },
    }"
  >
    <RouterView />
  </AConfigProvider>
</template>
