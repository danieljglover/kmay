FROM python:3.13-slim-bookworm
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && playwright install-deps chromium && useradd -m -u 1000 kmay && mkdir /data && chown kmay /data
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/browsers
RUN PLAYWRIGHT_BROWSERS_PATH=/opt/browsers playwright install chromium && chmod -R a+rX /opt/browsers
COPY *.py ./
USER kmay
ENV KMAY_DATA=/data
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8788", "--no-access-log", "--no-proxy-headers"]
