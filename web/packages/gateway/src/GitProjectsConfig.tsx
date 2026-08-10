import React, { Component } from 'react';
import './config.css';
import { getMutationHeaders } from './csrf';

interface GitProject {
  id: number;
  projectName: string;
  uri: string;
  productionMode?: boolean;
  productionBranch?: string | null;
  productionTagPattern?: string | null;
  exportGatewayResources?: boolean;
}

interface State {
  projects: GitProject[];
  loading: boolean;
  error: string | null;
  editing: GitProject | null;
  isNew: boolean;
}

export class GitProjectsConfig extends Component<{}, State> {
  constructor(props: {}) {
    super(props);
    this.state = {
      projects: [],
      loading: true,
      error: null,
      editing: null,
      isNew: false,
    };
  }

  async componentDidMount() {
    await this.loadProjects();
  }

  async loadProjects() {
    try {
      const response = await fetch('/data/git/projects', {
        credentials: 'same-origin',
        headers: {
          'Accept': 'application/json'
        }
      });
      if (!response.ok) throw new Error('Failed to load projects');
      const projects = await response.json();
      this.setState({ projects, loading: false, error: null });
    } catch (error) {
      this.setState({
        error: error instanceof Error ? error.message : 'Unknown error',
        loading: false
      });
    }
  }

  handleEdit = (project: GitProject) => {
    this.setState({ editing: { ...project }, isNew: false });
  };

  handleNew = () => {
    this.setState({
      editing: {
        id: 0,
        projectName: '',
        uri: '',
        productionMode: false,
        productionBranch: '',
        productionTagPattern: '',
        exportGatewayResources: false,
      },
      isNew: true
    });
  };

  handleDelete = async (id: number) => {
    if (!confirm('Are you sure you want to delete this project?')) return;

    try {
      const headers = await getMutationHeaders();
      const response = await fetch(`/data/git/projects/${id}`, {
        method: 'DELETE',
        credentials: 'same-origin',
        headers,
      });
      if (!response.ok) throw new Error('Failed to delete project');
      await this.loadProjects();
    } catch (error) {
      alert('Error deleting project: ' + error);
    }
  };

  handleSave = async () => {
    const { editing, isNew } = this.state;
    if (!editing) return;

    try {
      const url = isNew ? '/data/git/projects' : `/data/git/projects/${editing.id}`;
      const method = isNew ? 'POST' : 'PUT';

      const headers = await getMutationHeaders();
      const response = await fetch(url, {
        method,
        credentials: 'same-origin',
        headers,
        body: JSON.stringify(editing)
      });

      if (!response.ok) throw new Error('Failed to save project');

      this.setState({ editing: null, isNew: false });
      await this.loadProjects();
    } catch (error) {
      alert('Error saving project: ' + error);
    }
  };

  handleCancel = () => {
    this.setState({ editing: null, isNew: false });
  };

  handleChange = (field: keyof GitProject, value: string | boolean) => {
    this.setState(state => ({
      editing: state.editing ? { ...state.editing, [field]: value } : null
    }));
  };

  render() {
    const { projects, loading, error, editing } = this.state;

    if (loading) return <div className="git-config">Loading...</div>;
    if (error) return <div className="git-config error">Error: {error}</div>;

    return (
      <div className="git-config">
        <h2>Git Projects Configuration</h2>
        <p>Configure Git repository connections for your Ignition projects.</p>

        {editing ? (
          <div className="git-form">
            <h3>{this.state.isNew ? 'Add New Project' : 'Edit Project'}</h3>
            <div className="form-group">
              <label>Project Name:</label>
              <input
                type="text"
                value={editing.projectName}
                onChange={(e) => this.handleChange('projectName', e.target.value)}
                placeholder="MyProject"
              />
            </div>
            <div className="form-group">
              <label>Repository URI:</label>
              <input
                type="text"
                value={editing.uri}
                onChange={(e) => this.handleChange('uri', e.target.value)}
                placeholder="https://github.com/user/repo.git"
              />
              <small>Use https:// for username/password or git@github.com: for SSH</small>
            </div>
            <fieldset className="form-fieldset">
              <legend>Gateway Resources</legend>
              <div className="form-group">
                <label>
                  <input
                    type="checkbox"
                    checked={!!editing.exportGatewayResources}
                    onChange={(e) => this.handleChange('exportGatewayResources', e.target.checked)}
                  />
                  {' '}This project owns the gateway's tags, themes and images
                </label>
                <small>
                  Tags, themes and images belong to the gateway, not to a project, but they are
                  exported per project. Enable this on exactly one project so a single repository
                  owns them. Leave it off everywhere and they are never exported — project
                  resources still commit as normal.
                </small>
              </div>
            </fieldset>

            <fieldset className="form-fieldset">
              <legend>Production Mode</legend>
              <div className="form-group">
                <label>
                  <input
                    type="checkbox"
                    checked={!!editing.productionMode}
                    onChange={(e) => this.handleChange('productionMode', e.target.checked)}
                  />
                  {' '}Enable production mode
                </label>
                <small>Adds safety guards: pull checklist, push warnings, branch-switch block, and hotfix workflow on commits.</small>
              </div>
              <div className="form-group">
                <label>Production Branch:</label>
                <input
                  type="text"
                  value={editing.productionBranch ?? ''}
                  onChange={(e) => this.handleChange('productionBranch', e.target.value)}
                  placeholder="main"
                  disabled={!editing.productionMode}
                />
                <small>The protected branch (e.g. main, master, production).</small>
              </div>
              <div className="form-group">
                <label>Production Tag Pattern:</label>
                <input
                  type="text"
                  value={editing.productionTagPattern ?? ''}
                  onChange={(e) => this.handleChange('productionTagPattern', e.target.value)}
                  placeholder="v*"
                  disabled={!editing.productionMode}
                />
                <small>Wildcard (v*, release-?) or regex (^v\d+\.\d+\.\d+$). Leave blank to skip tag validation.</small>
              </div>
            </fieldset>
            <div className="button-group">
              <button onClick={this.handleSave} className="btn-primary">Save</button>
              <button onClick={this.handleCancel} className="btn-secondary">Cancel</button>
            </div>
          </div>
        ) : (
          <>
            <button onClick={this.handleNew} className="btn-primary">Add New Project</button>

            <table className="git-table">
              <thead>
                <tr>
                  <th>Project Name</th>
                  <th>Repository URI</th>
                  <th>Auth Type</th>
                  <th>Production</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {projects.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="empty">
                      No projects configured. Click "Add New Project" to get started.
                    </td>
                  </tr>
                ) : (
                  projects.map(project => (
                    <tr key={project.id}>
                      <td>{project.projectName}</td>
                      <td>{project.uri}</td>
                      <td>{project.uri.toLowerCase().startsWith('http') ? 'HTTPS' : 'SSH'}</td>
                      <td>
                        {project.productionMode ? (
                          <span className="prod-badge" title={`branch: ${project.productionBranch || '(unset)'}\ntag pattern: ${project.productionTagPattern || '(none)'}`}>PRODUCTION</span>
                        ) : (
                          <span className="prod-off">—</span>
                        )}
                      </td>
                      <td>
                        <button
                          onClick={() => this.handleEdit(project)}
                          className="btn-small"
                        >
                          Edit
                        </button>
                        <button
                          onClick={() => this.handleDelete(project.id)}
                          className="btn-small btn-danger"
                        >
                          Delete
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </>
        )}
      </div>
    );
  }
}

// Export for webpack to mount
export default GitProjectsConfig;
