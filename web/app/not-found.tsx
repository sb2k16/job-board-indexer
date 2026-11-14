import Link from "next/link";

export default function NotFound() {
  return (
    <div className="wrap">
      <div className="empty" style={{ margin: "48px 0" }}>
        <h1 style={{ fontSize: 22, marginBottom: 8 }}>Not found</h1>
        <p style={{ margin: "0 0 16px" }}>This job is not in the index. It may have been removed by the employer.</p>
        <Link className="btn" href="/jobs">Search jobs</Link>
      </div>
    </div>
  );
}
