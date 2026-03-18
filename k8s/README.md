# Nisaba Kubernetes Deployment

This directory contains Kubernetes manifests for deploying Nisaba.

## Prerequisites

- Kubernetes cluster (1.21+)
- kubectl configured
- NFS storage or compatible ReadWriteMany storage class
- (Optional) Ingress controller for external access

## Quick Start

1. **Configure secrets** - Edit `secret.yaml` with your credentials:
   ```bash
   # Generate base64 encoded passwords
   echo -n 'your-db-password' | base64
   ```

2. **Configure storage** - Edit `pvc.yaml` for your NFS setup:
   - Set the correct `storageClassName`
   - Or create a PersistentVolume manually

3. **Configure nodes** - Edit `configmap.yaml` to add your qBittorrent nodes

4. **Deploy**:
   ```bash
   kubectl apply -k .
   ```

5. **Check status**:
   ```bash
   kubectl -n nisaba get pods
   kubectl -n nisaba logs -f deployment/nisaba
   ```

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | PostgreSQL connection URL | `jdbc:postgresql://postgres:5432/nisaba` |
| `SPRING_DATASOURCE_USERNAME` | DB username | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | Required |
| `NISABA_AUTH_USERNAME` | API username for *arr clients | `admin` |
| `NISABA_AUTH_PASSWORD` | API password for *arr clients | Required |

### Node Configuration

Edit the `nodes.yml` section in `configmap.yaml`:

```yaml
nodes:
  - id: node-a
    baseUrl: http://qbittorrent-a:8080
    label: "Primary Node"
    clientType: qbittorrent
    credentials:
      username: admin
      password: secret123
```

### NFS Storage

Nisaba requires a shared storage volume accessible by all qBittorrent nodes. 
Configure the `nisaba-media` PVC in `pvc.yaml` to point to your NFS server.

## Scaling

Nisaba is designed as a single-instance service (no HA in v1). Do not scale 
replicas beyond 1 - multiple instances would conflict on EMA weight calculations.

## Monitoring

Health endpoints:
- `/health/live` - Liveness probe
- `/health/ready` - Readiness probe (includes DB connectivity)

## Updating

```bash
# Update image tag in kustomization.yaml, then:
kubectl apply -k .

# Or directly:
kubectl -n nisaba set image deployment/nisaba nisaba=ghcr.io/viewrr/nisaba:v0.2.0
```
