FROM python:3.13-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --disable-pip-version-check -r requirements.txt
COPY collector.py .
USER 1000:1000
ENTRYPOINT ["python", "-B", "/app/collector.py"]
