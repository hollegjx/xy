<script setup lang="ts">
import { ConfigProvider as AConfigProvider } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { RouterView } from 'vue-router'

const motionEase = 'cubic-bezier(0.22, 1, 0.36, 1)'
const motionSpring = 'cubic-bezier(0.34, 1.56, 0.64, 1)'
</script>

<template>
  <AConfigProvider
    :locale="zhCN"
    :auto-insert-space-in-button="false"
    :theme="{
      token: {
        colorPrimary: '#1d1d1f',
        colorInfo: '#1d1d1f',
        colorLink: '#1d1d1f',
        colorLinkHover: '#3a3a3c',
        colorLinkActive: '#000000',
        colorSuccess: '#248a3d',
        colorWarning: '#b25000',
        colorError: '#d70015',
        colorText: '#1d1d1f',
        colorTextSecondary: '#6e6e73',
        colorTextTertiary: '#86868b',
        colorBorder: '#d2d2d7',
        colorBorderSecondary: '#e8e8ed',
        colorBgLayout: '#f5f5f7',
        colorBgContainer: '#ffffff',
        colorFillAlter: '#f5f5f7',
        controlOutline: 'rgba(0, 0, 0, 0.06)',
        controlItemBgActive: '#f5f5f7',
        controlOutlineWidth: 3,
        borderRadius: 12,
        borderRadiusOuter: 12,
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
        motionEaseOutBack: motionSpring,
        motionEaseInBack: motionEase,
        motionEaseInQuint: motionEase,
        motionEaseOutQuint: motionEase,
        motionEaseOut: motionEase,
      },
      components: {
        Button: { controlOutlineWidth: 0 },
        Card: { boxShadow: 'none' },
        Table: { colorFillAlter: '#f5f5f7' },
      },
    }"
  >
    <RouterView />
  </AConfigProvider>
</template>
