import React from 'react';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
const cards = [{"title": "Make a first commit", "description": "Link a development project to a repository and inspect the changes.", "path": "quickstart"}, {"title": "Configure production mode", "description": "Read the pull restrictions and hotfix workflow before enabling production mode.", "path": "production-mode"}, {"title": "Use the scripting API", "description": "Read repository status or import tracked tags with system.git.", "path": "scripting-api"}];
export default function Home(): React.JSX.Element {
 return <Layout title="Ignition Git Module" description="Commit project resources, review history, and manage branches in Ignition 8.3.1+.">
  <main><section className="launch-hero"><p className="launch-label">IGNITION / DEVELOPER TOOLS</p><h1>Work with Git inside Designer.</h1><p className="lead">Commit project resources, review history, and manage branches in Ignition 8.3.1+.</p>
  <div className="launch-actions"><Link className="button button--primary button--lg" to="/docs/installation">Get started</Link><Link className="button button--outline button--primary button--lg" to="/docs/quickstart">Try a first workflow</Link></div></section>
  <section className="launch-grid" aria-label="Documentation paths">{cards.map(card => <article key={card.path}><h2>{card.title}</h2><p>{card.description}</p><Link to={'/docs/' + card.path}>Read the guide →</Link></article>)}</section>
  <aside className="launch-maintainer"><p>I’m Patrick Mannion. I work on Ignition development tools and write about the work on FIELDNOTES.</p><p><a href="https://awake-iris-z6ww.here.now/about/">About me</a> · <a href="https://www.linkedin.com/in/mannionpatrick/">LinkedIn</a> · <a href="https://x.com/__pattym__">X</a> · <a href="https://github.com/WhiskeyHouse/ignition-git-module/graphs/contributors">Project contributors</a></p></aside></main>
 </Layout>;
}
