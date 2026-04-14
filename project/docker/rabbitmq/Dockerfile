FROM rabbitmq:3.13.7-management

# The project declares x-delayed-message exchanges, so bake the plugin into the image.
COPY --chmod=644 docker/rabbitmq/rabbitmq_delayed_message_exchange-3.13.0.ez /opt/rabbitmq/plugins/

RUN rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
