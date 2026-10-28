import { defineConfig } from 'vitepress'

const base = process.env.DOCS_BASE_PATH || '/'
const siteOrigin = process.env.DOCS_SITE_ORIGIN || 'https://meridiandb.org'
const siteUrl = new URL(base, siteOrigin)

export default defineConfig({
  lang: 'en-US',
  title: 'MeridianDB',
  description: 'Exploring an embeddable temporal storage engine. Concepts, architecture and research decisions.',
  base,
  cleanUrls: true,
  lastUpdated: true,
  sitemap: { hostname: siteUrl.href },
  transformPageData(pageData) {
    if (pageData.isNotFound || pageData.relativePath === '404.md') return

    const route = pageData.relativePath
      .replace(/(^|\/)index\.md$/, '$1')
      .replace(/\.md$/, '')
    const head = pageData.frontmatter.head ?? []
    // Page data keeps the canonical current during client-side navigation too.
    pageData.frontmatter.head = head.filter(
      ([tag, attrs]) => !(tag === 'link' && attrs?.rel === 'canonical')
    )
    pageData.frontmatter.head.push([
      'link', { rel: 'canonical', href: new URL(route, siteUrl).href }
    ])
  },
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}favicon.svg` }],
    ['meta', { name: 'theme-color', content: '#0f766e' }]
  ],
  themeConfig: {
    logo: '/favicon.svg',
    nav: [
      { text: 'Concepts', link: '/concepts/temporal-data' },
      { text: 'Architecture', link: '/architecture/overview' },
      { text: 'Decisions', link: '/decisions/' },
      { text: 'Contribute', link: '/contributing/' }
    ],
    sidebar: [
      {
        text: 'Start here',
        items: [
          { text: 'Project overview', link: '/' },
          { text: 'Temporal data', link: '/concepts/temporal-data' }
        ]
      },
      {
        text: 'Design',
        items: [
          { text: 'Architecture', link: '/architecture/overview' },
          { text: 'Decision records', link: '/decisions/' },
          { text: 'Language strategy', link: '/decisions/0001-language-strategy' }
        ]
      },
      {
        text: 'Contributing',
        items: [
          { text: 'Working on MeridianDB', link: '/contributing/' },
          { text: 'Documentation and publishing', link: '/contributing/documentation' }
        ]
      }
    ],
    search: { provider: 'local' },
    outline: [2, 3],
    editLink: {
      pattern: 'https://github.com/MeridianDB/meridiandb/edit/main/docs/:path',
      text: 'Edit this page on GitHub'
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/MeridianDB/meridiandb' }
    ],
    footer: {
      message: 'Open source under the Apache License 2.0. Experimental project.'
    }
  }
})
