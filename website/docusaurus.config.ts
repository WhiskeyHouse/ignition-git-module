import type {Config} from '@docusaurus/types';
const config: Config = {
  "title": "Ignition Git Module",
  "tagline": "Commit project resources, review history, and manage branches in Ignition 8.3.1+.",
  "favicon": "img/favicon.svg",
  "url": "https://whiskeyhouse.github.io",
  "baseUrl": "/ignition-git-module/",
  "trailingSlash": true,
  "organizationName": "WhiskeyHouse",
  "projectName": "ignition-git-module",
  "onBrokenLinks": "throw",
  "markdown": {
    "format": "md",
    "hooks": {
      "onBrokenMarkdownLinks": "throw"
    }
  },
  "presets": [
    [
      "classic",
      {
        "docs": {
          "path": "../docs",
          "sidebarPath": "./sidebars.ts",
          "editUrl": "https://github.com/WhiskeyHouse/ignition-git-module/edit/main/docs/",
          "exclude": [
            "superpowers/**",
            "*_SUMMARY.md",
            "*_REPORT.md",
            "*-STRATEGY.md",
            "*_COMPLETE.md",
            "AI_DEVELOPMENT_RULES.md",
            "BINDING_PATTERNS_ANALYSIS.md",
            "GETTING_STARTED.md",
            "LINTER_USAGE.md",
            "SUPPRESSION.md",
            "PROJECT_OVERVIEW.md",
            "IGNITION-LINTER-INTEGRATION.md"
          ]
        },
        "blog": false,
        "theme": {
          "customCss": "./src/css/custom.css"
        }
      }
    ]
  ],
  "themeConfig": {
    "colorMode": {
      "defaultMode": "dark",
      "respectPrefersColorScheme": true
    },
    "navbar": {
      "title": "Ignition Git Module",
      "items": [
        {
          "to": "/docs/installation",
          "label": "Get started",
          "position": "left"
        },
        {
          "type": "docSidebar",
          "sidebarId": "docsSidebar",
          "label": "Docs",
          "position": "left"
        },
        {
          "label": "Tools",
          "type": "dropdown",
          "items": [
            {
              "label": "Ignition Dev Tools",
              "href": "https://thethoughtagen.github.io/ignition-ide-plugins/"
            },
            {
              "label": "ignition-lint",
              "href": "https://thethoughtagen.github.io/ignition-lint/"
            },
            {
              "label": "ignition-cli",
              "href": "https://thethoughtagen.github.io/ignition-cli/"
            },
            {
              "label": "ignition-mcp",
              "href": "https://whiskeyhouse.github.io/ignition-mcp/"
            }
          ],
          "position": "left"
        },
        {
          "href": "https://github.com/WhiskeyHouse/ignition-git-module/releases",
          "label": "Releases",
          "position": "right"
        },
        {
          "href": "https://github.com/WhiskeyHouse/ignition-git-module",
          "label": "GitHub",
          "position": "right"
        }
      ]
    },
    "footer": {
      "style": "dark",
      "links": [
        {
          "title": "Documentation",
          "items": [
            {
              "label": "Installation",
              "to": "/docs/installation"
            },
            {
              "label": "First steps",
              "to": "/docs/quickstart"
            },
            {
              "label": "Report an issue",
              "href": "https://github.com/WhiskeyHouse/ignition-git-module/issues"
            }
          ]
        },
        {
          "title": "Related tools",
          "items": [
            {
              "label": "Ignition Dev Tools",
              "href": "https://thethoughtagen.github.io/ignition-ide-plugins/"
            },
            {
              "label": "ignition-lint",
              "href": "https://thethoughtagen.github.io/ignition-lint/"
            },
            {
              "label": "ignition-cli",
              "href": "https://thethoughtagen.github.io/ignition-cli/"
            },
            {
              "label": "ignition-mcp",
              "href": "https://whiskeyhouse.github.io/ignition-mcp/"
            }
          ]
        },
        {
          "title": "Patrick Mannion",
          "items": [
            {
              "label": "FIELDNOTES",
              "href": "https://awake-iris-z6ww.here.now/"
            },
            {
              "label": "LinkedIn",
              "href": "https://www.linkedin.com/in/mannionpatrick/"
            },
            {
              "label": "X",
              "href": "https://x.com/__pattym__"
            }
          ]
        }
      ],
      "copyright": "Community tooling for Ignition. See each repository for its license and contributors."
    }
  }
};
export default config;
