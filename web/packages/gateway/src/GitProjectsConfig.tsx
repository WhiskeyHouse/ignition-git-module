import React, { Component } from 'react';
import './config.css';

interface GitProject {
  id: number;
  projectName: string;
  uri: string;
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
      isNew: false
    };
  }

  componentDidMount() {
    this.loadProjects();
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
      editing: { id: 0, projectName: '', uri: '' },
      isNew: true
    });
  };

  handleDelete = async (id: number) => {
    if (!confirm('Are you sure you want to delete this project?')) return;

    try {
      const response = await fetch(`/data/git/projects/${id}`, {
        method: 'DELETE',
        credentials: 'same-origin',
        headers: {
          'Accept': 'application/json'
        }
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

      const response = await fetch(url, {
        method,
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json'
        },
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

  handleChange = (field: keyof GitProject, value: string) => {
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
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {projects.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="empty">
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
