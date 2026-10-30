FROM python:3.12-alpine
WORKDIR /app
COPY server.py .
ENV PYTHONUNBUFFERED=1
EXPOSE 8765
CMD ["python", "server.py"]
