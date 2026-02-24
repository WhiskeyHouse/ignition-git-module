import React, { Component } from 'react';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import './docs.css';

interface TreeNode {
  name: string;
  type: 'file' | 'directory';
  path: string;
  children?: TreeNode[];
}

interface ProjectInfo {
  name: string;
}

interface State {
  projects: ProjectInfo[];
  selectedProject: string;
  tree: TreeNode | null;
  selectedPath: string;
  content: string;
  loadingTree: boolean;
  loadingContent: boolean;
  error: string | null;
  expandedDirs: Set<string>;
  mermaidReady: boolean;
}

export class DocsViewer extends Component<{}, State> {
  private mermaidModule: any = null;
  private mermaidIdCounter: number = 0;
  private contentRef: React.RefObject<HTMLDivElement>;
  // Store initial deep-link params as instance vars so they survive
  // React 18 async batching and setState timing issues
  private initialProject: string;
  private initialPath: string;

  constructor(props: {}) {
    super(props);
    this.contentRef = React.createRef();

    // Parse hash params for deep linking
    const hashParams = this.parseHash();
    this.initialProject = hashParams.project;
    this.initialPath = hashParams.path;

    this.state = {
      projects: [],
      selectedProject: this.initialProject,
      tree: null,
      selectedPath: this.initialPath,
      content: '',
      loadingTree: false,
      loadingContent: false,
      error: null,
      expandedDirs: new Set(),
      mermaidReady: false,
    };
  }

  parseHash(): { project: string; path: string } {
    // Check sessionStorage first (set by the /data/git/docs/redirect endpoint
    // to survive Ignition's SPA routing which strips hash/query params)
    try {
      const stored = sessionStorage.getItem('git-docs-deeplink');
      if (stored) {
        sessionStorage.removeItem('git-docs-deeplink');
        const parsed = JSON.parse(stored);
        if (parsed.project || parsed.path) {
          return { project: parsed.project || '', path: parsed.path || '' };
        }
      }
    } catch (e) {
      // Fall through to hash/query params
    }

    // Fall back to hash fragment or query params
    const hash = window.location.hash.slice(1);
    const hashParams = new URLSearchParams(hash);
    const queryParams = new URLSearchParams(window.location.search);
    return {
      project: hashParams.get('project') || queryParams.get('project') || '',
      path: hashParams.get('path') || queryParams.get('path') || '',
    };
  }

  updateHash(project: string, path: string) {
    const params = new URLSearchParams();
    if (project) params.set('project', project);
    if (path) params.set('path', path);
    window.location.hash = params.toString();
  }

  async componentDidMount() {
    await this.loadProjects();
    this.initMermaid();
  }

  async initMermaid() {
    try {
      const mod = await import('mermaid');
      this.mermaidModule = mod.default;
      this.mermaidModule.initialize({
        startOnLoad: false,
        theme: 'default',
        securityLevel: 'strict',
      });
      this.setState({ mermaidReady: true });
    } catch (e) {
      // Mermaid is optional - diagrams just won't render
      console.warn('Mermaid failed to load:', e);
    }
  }

  async loadProjects() {
    try {
      const response = await fetch('/data/git/docs/projects', {
        credentials: 'same-origin',
        headers: { 'Accept': 'application/json' },
      });
      if (!response.ok) throw new Error('Failed to load projects');
      const projects: ProjectInfo[] = await response.json();
      this.setState({ projects });

      // Auto-select from hash or first project
      if (this.initialProject && projects.some(p => p.name === this.initialProject)) {
        const pathToLoad = this.initialPath;
        this.initialPath = ''; // Clear after first use
        await this.loadTree(this.initialProject, pathToLoad);
      } else if (projects.length > 0) {
        this.setState({ selectedProject: projects[0].name });
        await this.loadTree(projects[0].name);
      }
    } catch (e) {
      this.setState({ error: e instanceof Error ? e.message : 'Failed to load projects' });
    }
  }

  async loadTree(projectName: string, initialPath?: string) {
    this.setState({ loadingTree: true, tree: null, error: null });
    try {
      const response = await fetch(`/data/git/docs/tree/${encodeURIComponent(projectName)}`, {
        credentials: 'same-origin',
        headers: { 'Accept': 'application/json' },
      });
      if (!response.ok) throw new Error('Failed to load file tree');
      const tree: TreeNode = await response.json();

      // Auto-expand root
      const expandedDirs = new Set<string>();
      expandedDirs.add(tree.path);

      this.setState({ tree, loadingTree: false, expandedDirs });

      // Use the explicitly passed path (avoids stale this.state reads after async/setState)
      if (initialPath) {
        this.expandPathParents(initialPath, tree, expandedDirs);
        this.setState({ expandedDirs: new Set(expandedDirs) });
        await this.loadContent(projectName, initialPath);
      } else {
        // Auto-select first file (README.md preferred)
        const firstFile = this.findFirstFile(tree);
        if (firstFile) {
          this.expandPathParents(firstFile, tree, expandedDirs);
          this.setState({ expandedDirs: new Set(expandedDirs) });
          await this.loadContent(projectName, firstFile);
        }
      }
    } catch (e) {
      this.setState({
        loadingTree: false,
        error: e instanceof Error ? e.message : 'Failed to load tree',
      });
    }
  }

  findFirstFile(node: TreeNode): string | null {
    if (!node.children) return null;

    // Prefer README.md at current level
    for (const child of node.children) {
      if (child.type === 'file' && child.name.toLowerCase().startsWith('readme')) {
        return child.path;
      }
    }

    // Otherwise first file
    for (const child of node.children) {
      if (child.type === 'file') return child.path;
    }

    // Recurse into directories
    for (const child of node.children) {
      if (child.type === 'directory') {
        const found = this.findFirstFile(child);
        if (found) return found;
      }
    }

    return null;
  }

  expandPathParents(filePath: string, tree: TreeNode, expandedDirs: Set<string>) {
    // Expand all parent directories of the given path
    const parts = filePath.split(/[/\\]/);
    let current = '';
    for (let i = 0; i < parts.length - 1; i++) {
      current = current ? current + '/' + parts[i] : parts[i];
      expandedDirs.add(current);
    }
    // Also expand root
    expandedDirs.add(tree.path);
  }

  async loadContent(projectName: string, filePath: string) {
    this.setState({ loadingContent: true, selectedPath: filePath, content: '', error: null });
    this.updateHash(projectName, filePath);
    try {
      const response = await fetch(
        `/data/git/docs/content/${encodeURIComponent(projectName)}?path=${encodeURIComponent(filePath)}`,
        {
          credentials: 'same-origin',
          headers: { 'Accept': 'application/json' },
        }
      );
      if (!response.ok) throw new Error('Failed to load file content');
      const data = await response.json();
      this.setState({ content: data.content, loadingContent: false }, () => {
        this.renderMermaidDiagrams();
      });
    } catch (e) {
      this.setState({
        loadingContent: false,
        error: e instanceof Error ? e.message : 'Failed to load content',
      });
    }
  }

  async renderMermaidDiagrams() {
    if (!this.mermaidModule || !this.contentRef.current) return;

    const containers = this.contentRef.current.querySelectorAll('.docs-mermaid-container');
    for (let i = 0; i < containers.length; i++) {
      const container = containers[i] as HTMLElement;
      const code = container.getAttribute('data-mermaid');
      if (!code) continue;

      try {
        const id = `mermaid-${++this.mermaidIdCounter}`;
        const { svg } = await this.mermaidModule.render(id, code);
        container.innerHTML = svg;
      } catch (e) {
        container.innerHTML = `<div class="docs-mermaid-error">Failed to render diagram: ${
          e instanceof Error ? e.message : 'unknown error'
        }</div><pre><code>${this.escapeHtml(code)}</code></pre>`;
      }
    }
  }

  escapeHtml(text: string): string {
    const div = document.createElement('div');
    div.appendChild(document.createTextNode(text));
    return div.innerHTML;
  }

  renderMarkdown(content: string): string {
    // Custom renderer to intercept mermaid code blocks
    const renderer = new marked.Renderer();
    const originalCode = renderer.code;

    renderer.code = function ({ text, lang }: { text: string; lang?: string; escaped?: boolean }) {
      if (lang === 'mermaid') {
        // Encode the mermaid source as a data attribute for post-render processing
        const escaped = text
          .replace(/&/g, '&amp;')
          .replace(/"/g, '&quot;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;');
        return `<div class="docs-mermaid-container" data-mermaid="${escaped}">Loading diagram...</div>`;
      }
      // Fall back to default code rendering
      if (originalCode) {
        return originalCode.call(this, { text, lang, escaped: false });
      }
      const escapedText = text.replace(/</g, '&lt;').replace(/>/g, '&gt;');
      return `<pre><code class="language-${lang || ''}">${escapedText}</code></pre>`;
    };

    const rawHtml = marked(content, { renderer, async: false }) as string;
    return DOMPurify.sanitize(rawHtml, {
      ADD_ATTR: ['data-mermaid'],
      ADD_TAGS: ['div'],
    });
  }

  handleProjectChange = async (e: React.ChangeEvent<HTMLSelectElement>) => {
    const projectName = e.target.value;
    this.setState({
      selectedProject: projectName,
      selectedPath: '',
      content: '',
      tree: null,
    });
    this.updateHash(projectName, '');
    await this.loadTree(projectName);
  };

  handleFileClick = async (path: string) => {
    const { selectedProject } = this.state;
    await this.loadContent(selectedProject, path);
  };

  handleDirToggle = (path: string) => {
    this.setState(state => {
      const expandedDirs = new Set(state.expandedDirs);
      if (expandedDirs.has(path)) {
        expandedDirs.delete(path);
      } else {
        expandedDirs.add(path);
      }
      return { expandedDirs };
    });
  };

  renderTreeNode = (node: TreeNode, depth: number = 0): React.ReactNode => {
    const { selectedPath, expandedDirs } = this.state;

    if (node.type === 'file') {
      const isActive = node.path === selectedPath;
      return (
        <div key={node.path} className="docs-tree-item" style={{ paddingLeft: depth * 16 }}>
          <div
            className={`docs-tree-label${isActive ? ' active' : ''}`}
            onClick={() => this.handleFileClick(node.path)}
            title={node.path}
          >
            <span className="docs-tree-icon">&#128196;</span>
            {node.name}
          </div>
        </div>
      );
    }

    const isExpanded = expandedDirs.has(node.path);
    return (
      <div key={node.path || '__root'} className="docs-tree-item">
        <div
          className="docs-tree-label"
          style={{ paddingLeft: depth * 16 }}
          onClick={() => this.handleDirToggle(node.path)}
        >
          <span className="docs-tree-icon">{isExpanded ? '\u25BC' : '\u25B6'}</span>
          {node.name}
        </div>
        {isExpanded && node.children && (
          <div className="docs-tree-children">
            {node.children.map(child => this.renderTreeNode(child, depth + 1))}
          </div>
        )}
      </div>
    );
  };

  render() {
    const {
      projects, selectedProject, tree, selectedPath,
      content, loadingTree, loadingContent, error,
    } = this.state;

    return (
      <div className="docs-viewer">
        <div className="docs-toolbar">
          <label>Project:</label>
          <select value={selectedProject} onChange={this.handleProjectChange}>
            {projects.length === 0 && <option value="">No projects with docs</option>}
            {projects.map(p => (
              <option key={p.name} value={p.name}>{p.name}</option>
            ))}
          </select>
        </div>

        {error && <div className="docs-error">{error}</div>}

        <div className="docs-main">
          <div className="docs-sidebar">
            {loadingTree ? (
              <div className="docs-loading">Loading...</div>
            ) : tree ? (
              this.renderTreeNode(tree)
            ) : (
              <div className="docs-loading">Select a project</div>
            )}
          </div>

          <div className="docs-content" ref={this.contentRef as React.RefObject<HTMLDivElement>}>
            {loadingContent ? (
              <div className="docs-loading">Loading...</div>
            ) : content ? (
              <>
                {selectedPath && (
                  <div className="docs-breadcrumb">{selectedPath}</div>
                )}
                <div
                  className="docs-markdown"
                  dangerouslySetInnerHTML={{
                    __html: this.renderMarkdown(content),
                  }}
                />
              </>
            ) : (
              <div className="docs-content-empty">
                Select a file from the sidebar to view documentation
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }
}

export default DocsViewer;
