FROM python:3.12-slim
WORKDIR /srv
COPY backend/requirements.txt /srv/backend/
RUN pip install --no-cache-dir -r backend/requirements.txt
COPY backend/server.py /srv/backend/
COPY mobile/app/src/main/assets/index.html mobile/app/src/main/assets/field.js mobile/app/src/main/assets/field.css mobile/app/src/main/assets/shrub.jpg mobile/app/src/main/assets/vanarekha.png mobile/app/src/main/assets/google-sans.ttf mobile/app/src/main/assets/google-sans-license.txt /srv/mobile/app/src/main/assets/
CMD ["sh","-c","exec gunicorn --bind 0.0.0.0:${PORT:-8080} --workers 2 --threads 4 backend.server:app"]
