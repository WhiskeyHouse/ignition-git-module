import React, { Component } from 'react';
import './config.css';
import { getMutationHeaders } from './csrf';

// Response from API - uses boolean flags instead of raw secrets
interface GitUser {
  id: number;
  projectName: string;
  ignitionUser: string;
  userName: string;
  email: string;
  hasPassword: boolean;
  hasSshKey: boolean;
}

// Form data for editing - includes raw values when updating
interface GitUserForm {
  id: number;
  projectName: string;
  ignitionUser: string;
  userName: string;
  email: string;
  password: string;
  sshKey: string;
}

interface State {
  users: GitUser[];
  projects: string[];
  loading: boolean;
  error: string | null;
  editing: GitUserForm | null;
  isNew: boolean;
}

export class GitUsersConfig extends Component<{}, State> {
  constructor(props: {}) {
    super(props);
    this.state = {
      users: [],
      projects: [],
      loading: true,
      error: null,
      editing: null,
      isNew: false,
    };
  }

  async componentDidMount() {
    await this.loadData();
  }

  async loadData() {
    try {
      const [usersRes, projectsRes] = await Promise.all([
        fetch('/data/git/users', {
          credentials: 'same-origin',
          headers: { 'Accept': 'application/json' }
        }),
        fetch('/data/git/projects', {
          credentials: 'same-origin',
          headers: { 'Accept': 'application/json' }
        })
      ]);

      if (!usersRes.ok || !projectsRes.ok) throw new Error('Failed to load data');

      const users = await usersRes.json();
      const projectsData = await projectsRes.json();
      const projects = projectsData.map((p: any) => p.projectName);

      this.setState({ users, projects, loading: false });
    } catch (error) {
      this.setState({
        error: error instanceof Error ? error.message : 'Unknown error',
        loading: false
      });
    }
  }

  handleEdit = (user: GitUser) => {
    // Convert GitUser (with flags) to GitUserForm (with empty secrets for editing)
    this.setState({
      editing: {
        id: user.id,
        projectName: user.projectName,
        ignitionUser: user.ignitionUser,
        userName: user.userName,
        email: user.email,
        password: '', // Start empty - user can update if needed
        sshKey: ''    // Start empty - user can update if needed
      },
      isNew: false
    });
  };

  handleNew = () => {
    this.setState({
      editing: {
        id: 0,
        projectName: this.state.projects[0] || '',
        ignitionUser: '',
        userName: '',
        email: '',
        password: '',
        sshKey: ''
      },
      isNew: true
    });
  };

  handleDelete = async (id: number) => {
    if (!confirm('Are you sure you want to delete this user?')) return;

    try {
      const headers = await getMutationHeaders();
      const response = await fetch(`/data/git/users/${id}`, {
        method: 'DELETE',
        credentials: 'same-origin',
        headers,
      });
      if (!response.ok) throw new Error('Failed to delete user');
      await this.loadData();
    } catch (error) {
      alert('Error deleting user: ' + error);
    }
  };

  handleSave = async () => {
    const { editing, isNew } = this.state;
    if (!editing) return;

    // Validation
    if (!editing.ignitionUser || !editing.email) {
      alert('Ignition User and Email are required');
      return;
    }

    // For new users, require at least one auth method
    if (isNew && !editing.password && !editing.sshKey) {
      alert('Either Password or SSH Key must be provided');
      return;
    }

    // For updates, only send password/sshKey if they were changed
    // If empty, the backend will preserve the existing values
    try {
      const url = isNew ? '/data/git/users' : `/data/git/users/${editing.id}`;
      const method = isNew ? 'POST' : 'PUT';

      const headers = await getMutationHeaders();
      const response = await fetch(url, {
        method,
        credentials: 'same-origin',
        headers,
        body: JSON.stringify(editing)
      });

      if (!response.ok) throw new Error('Failed to save user');

      this.setState({ editing: null, isNew: false });
      await this.loadData();
    } catch (error) {
      alert('Error saving user: ' + error);
    }
  };

  handleCancel = () => {
    this.setState({ editing: null, isNew: false });
  };

  handleChange = (field: keyof GitUserForm, value: string) => {
    this.setState(state => ({
      editing: state.editing ? { ...state.editing, [field]: value } : null
    }));
  };

  render() {
    const { users, projects, loading, error, editing } = this.state;

    if (loading) return <div className="git-config">Loading...</div>;
    if (error) return <div className="git-config error">Error: {error}</div>;

    return (
      <div className="git-config">
        <h2>Git Users Configuration</h2>
        <p>Configure Git user credentials for Ignition users per project.</p>

        {editing ? (
          <div className="git-form">
            <h3>{this.state.isNew ? 'Add New User' : 'Edit User'}</h3>

            <div className="form-group">
              <label>Project:</label>
              <select
                value={editing.projectName}
                onChange={(e) => this.handleChange('projectName', e.target.value)}
              >
                {projects.map(proj => (
                  <option key={proj} value={proj}>{proj}</option>
                ))}
              </select>
            </div>

            <div className="form-group">
              <label>Ignition User: *</label>
              <input
                type="text"
                value={editing.ignitionUser}
                onChange={(e) => this.handleChange('ignitionUser', e.target.value)}
                placeholder="admin"
              />
            </div>

            <div className="form-group">
              <label>Git Username:</label>
              <input
                type="text"
                value={editing.userName}
                onChange={(e) => this.handleChange('userName', e.target.value)}
                placeholder="git-username"
              />
            </div>

            <div className="form-group">
              <label>Email: *</label>
              <input
                type="email"
                value={editing.email}
                onChange={(e) => this.handleChange('email', e.target.value)}
                placeholder="user@example.com"
              />
            </div>

            <div className="form-group">
              <label>Password:</label>
              <input
                type="password"
                value={editing.password}
                onChange={(e) => this.handleChange('password', e.target.value)}
                placeholder={this.state.isNew ? "Enter password" : "Leave empty to keep existing"}
              />
              <small>
                For HTTPS authentication
                {!this.state.isNew && ' - Leave empty to keep current password'}
              </small>
            </div>

            <div className="form-group">
              <label>SSH Private Key:</label>
              <textarea
                value={editing.sshKey}
                onChange={(e) => this.handleChange('sshKey', e.target.value)}
                placeholder={this.state.isNew ? "-----BEGIN RSA PRIVATE KEY-----" : "Leave empty to keep existing"}
                rows={5}
              />
              <small>
                For SSH authentication
                {!this.state.isNew && ' - Leave empty to keep current SSH key'}
              </small>
            </div>

            <div className="button-group">
              <button onClick={this.handleSave} className="btn-primary">Save</button>
              <button onClick={this.handleCancel} className="btn-secondary">Cancel</button>
            </div>
          </div>
        ) : (
          <>
            <button
              onClick={this.handleNew}
              className="btn-primary"
              disabled={projects.length === 0}
            >
              Add New User
            </button>

            {projects.length === 0 && (
              <p className="warning">
                Please configure at least one Git project before adding users.
              </p>
            )}

            <table className="git-table">
              <thead>
                <tr>
                  <th>Project</th>
                  <th>Ignition User</th>
                  <th>Git Username</th>
                  <th>Email</th>
                  <th>Auth Method</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {users.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="empty">
                      No users configured. Click "Add New User" to get started.
                    </td>
                  </tr>
                ) : (
                  users.map(user => (
                    <tr key={user.id}>
                      <td>{user.projectName}</td>
                      <td>{user.ignitionUser}</td>
                      <td>{user.userName || '-'}</td>
                      <td>{user.email}</td>
                      <td>
                        {user.hasSshKey && user.hasPassword ? 'SSH + Password' :
                         user.hasSshKey ? 'SSH' :
                         user.hasPassword ? 'Password' : 'None'}
                      </td>
                      <td>
                        <button
                          onClick={() => this.handleEdit(user)}
                          className="btn-small"
                        >
                          Edit
                        </button>
                        <button
                          onClick={() => this.handleDelete(user.id)}
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
export default GitUsersConfig;
