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
          name: 'home',
          component: () => import('@/pages/app/HomePage.vue'),
          meta: { title: '首页' },
        },
        {
          path: 'apps/:id',
          name: 'app-workbench',
          component: () => import('@/pages/app/AppWorkbenchPage.vue'),
          meta: { title: '应用工作台', requiresAuth: true },
        },
        {
          path: 'admin/users',
          name: 'admin-users',
          component: () => import('@/pages/user/UserListPage.vue'),
          meta: { title: '用户管理', requiresAuth: true, adminOnly: true },
        },
        {
          path: 'admin/apps',
          name: 'admin-apps',
          component: () => import('@/pages/app/AppListPage.vue'),
          meta: { title: '应用管理', requiresAuth: true, adminOnly: true },
        },
        {
          path: 'system/status',
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
        {
          path: 'user/login',
          name: 'user-login',
          component: () => import('@/pages/user/LoginPage.vue'),
          meta: { title: '登录' },
        },
      ],
    },
  ],
})

router.afterEach((to) => {
  document.title = `${String(to.meta.title ?? '星云')} · XY`
})

export default router
