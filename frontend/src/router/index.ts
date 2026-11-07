import { createRouter, createWebHistory } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: BasicLayout,
      children: [
        {
          path: '',
          name: 'system-status',
          component: () => import('@/pages/system/StatusPage.vue'),
          meta: { title: '系统状态' },
        },
        {
          path: 'user/register',
          name: 'user-register',
          component: () => import('@/pages/user/RegisterPage.vue'),
          meta: { title: '注册账号' },
        },
      ],
    },
  ],
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? '星云')} · XY`
})

export default router
